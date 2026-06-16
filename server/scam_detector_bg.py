#!/opt/vocaguard-venv/bin/python3
"""
VocaGuard background scam detector — reads audio from a MixMonitor FIFO.
Called from the vocaguard-user Asterisk context via System() when the
user accepts a call and a SIP bridge is established.
Usage: scam_detector_bg.py <audio_fifo_path> <caller_number>
"""
import sys, os, json, logging, audioop, time
from collections import deque
import importlib.util

# ---------------------------------------------------------------------------
# Import shared detection logic from scam_detector.py.
# This also initialises Firebase Admin SDK (module-level code there), which
# is fine since this is a fresh process per call.
# ---------------------------------------------------------------------------
_spec = importlib.util.spec_from_file_location(
    "scam_detector", "/opt/vocaguard/scam_detector.py"
)
_sd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_sd)

detect_scam         = _sd.detect_scam
score_to_confidence = _sd.score_to_confidence
classify_scam_type  = _sd.classify_scam_type
send_fcm_alert      = _sd.send_fcm_alert

import numpy as np
from vosk import Model, KaldiRecognizer
from faster_whisper import WhisperModel

MODEL_PATH_EN       = "/opt/vocaguard/model-en"
SAMPLE_RATE         = 16000
WHISPER_CHUNK_BYTES = 16000 * 2 * 2   # 2-second chunks at 16 kHz
WINDOW_SECONDS      = 90

logging.basicConfig(
    filename="/var/log/vocaguard_agi.log",
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s"
)
log = logging.getLogger("vocaguard.bg")

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if len(sys.argv) < 3:
        log.error("Usage: scam_detector_bg.py <audio_fifo> <caller_number>")
        sys.exit(1)

    audio_path    = sys.argv[1]
    caller_number = sys.argv[2]
    log.info(f"BG detector started: fifo={audio_path} caller={caller_number}")

    try:
        rec_en = KaldiRecognizer(Model(MODEL_PATH_EN), SAMPLE_RATE)
        log.info("BG: Vosk model loaded")
    except Exception as e:
        log.error(f"BG: Vosk load error: {e}")
        return

    whisper_model = None
    try:
        whisper_model = WhisperModel("base", device="cpu", compute_type="int8")
        log.info("BG: Whisper model loaded")
    except Exception as e:
        log.warning(f"BG: Whisper load error: {e}")

    alerted        = False
    resample_state = None
    whisper_buf    = bytearray()
    transcript_win  = deque()
    transcript_disp = ""

    def add_text(text, label):
        nonlocal transcript_disp
        transcript_win.append((time.monotonic(), text))
        transcript_disp += " " + text
        log.info(f"BG STT-{label}: {text}")

    def get_window_text():
        now = time.monotonic()
        while transcript_win and now - transcript_win[0][0] > WINDOW_SECONDS:
            transcript_win.popleft()
        return " ".join(t for _, t in transcript_win)

    def check_and_alert(label):
        nonlocal alerted
        if alerted:
            return
        is_scam, matched_kws, score = detect_scam(get_window_text())
        if is_scam:
            conf = score_to_confidence(score)
            scam_type = classify_scam_type(matched_kws)
            log.warning(f"BG SCAM ({label}): {scam_type} score={score}")
            send_fcm_alert(matched_kws, transcript_disp.strip(),
                           scam_type, caller_number, conf)
            alerted = True

    # Open FIFO for reading — blocks until MixMonitor connects as writer.
    # MixMonitor writes 8 kHz SLIN (raw PCM, no header), same format as EAGI fd3.
    try:
        with open(audio_path, "rb") as audio_file:
            log.info("BG: FIFO connected, analysis running")
            while True:
                data = audio_file.read(3200)
                if not data:
                    log.info("BG: audio stream ended")
                    break

                # Upsample 8 kHz → 16 kHz for Vosk/Whisper
                resampled, resample_state = audioop.ratecv(
                    data, 2, 1, 8000, 16000, resample_state
                )

                if rec_en.AcceptWaveform(resampled):
                    text = json.loads(rec_en.Result()).get("text", "").strip()
                    if text:
                        add_text(text, "EN")
                        check_and_alert("vosk-en")

                whisper_buf.extend(resampled)
                if len(whisper_buf) >= WHISPER_CHUNK_BYTES:
                    if whisper_model:
                        try:
                            audio_np = (
                                np.frombuffer(bytes(whisper_buf), dtype=np.int16)
                                  .astype(np.float32) / 32768.0
                            )
                            segments, info = whisper_model.transcribe(
                                audio_np, language=None, beam_size=1, best_of=1,
                                vad_filter=True,
                            )
                            text = " ".join(seg.text for seg in segments).strip()
                            if text:
                                add_text(text, f"ML({info.language})")
                                check_and_alert("whisper-ml")
                        except Exception as e:
                            log.warning(f"BG Whisper error: {e}")
                    whisper_buf.clear()

    except Exception as e:
        log.info(f"BG audio closed: {e}")

    # Final flush
    final_en = json.loads(rec_en.FinalResult()).get("text", "").strip()
    if final_en:
        add_text(final_en, "EN-final")

    if whisper_buf and whisper_model:
        try:
            audio_np = (
                np.frombuffer(bytes(whisper_buf), dtype=np.int16)
                  .astype(np.float32) / 32768.0
            )
            segments, info = whisper_model.transcribe(
                audio_np, language=None, beam_size=1, best_of=1, vad_filter=True,
            )
            text = " ".join(seg.text for seg in segments).strip()
            if text:
                add_text(text, f"ML({info.language})-final")
        except Exception as e:
            log.warning(f"BG Whisper final error: {e}")

    check_and_alert("final")
    log.info(f"BG call ended. transcript={transcript_disp.strip()!r}")
    try:
        os.unlink(audio_path)
    except Exception:
        pass


if __name__ == "__main__":
    main()
