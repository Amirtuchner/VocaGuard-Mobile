#!/opt/vocaguard-venv/bin/python3
"""
VocaGuard background scam detector — reads audio from a MixMonitor FIFO.
Called from the vocaguard-user Asterisk context via System() when the
user accepts a call and a SIP bridge is established.
Usage: scam_detector_bg.py <audio_fifo_path> <caller_number> [user_phone]
  user_phone — the original called number (from DIVERSION), used to look up
               the correct FCM token in the multi-tenant user database.
"""
import sys, os, json, logging, audioop, time, sqlite3
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

detect_scam          = _sd.detect_scam
detect_scam_combined = _sd.detect_scam_combined
score_to_confidence  = _sd.score_to_confidence
classify_scam_type   = _sd.classify_scam_type
send_fcm_alert       = _sd.send_fcm_alert

DB_PATH        = "/opt/vocaguard/users.db"
FCM_TOKEN_FILE = "/opt/vocaguard/fcm_token.txt"


def get_fcm_token_for_number(phone_number: str) -> str:
    """Look up FCM token by original called number (suffix match).
    DIDWW strips the country code prefix, so we match on trailing digits.
    Falls back to single-user token file.
    """
    if phone_number:
        try:
            conn = sqlite3.connect(DB_PATH)
            row = conn.execute(
                "SELECT fcm_token FROM users WHERE phone_number LIKE ?",
                (f"%{phone_number}",)
            ).fetchone()
            conn.close()
            if row and row[0]:
                return row[0]
        except Exception:
            pass
    try:
        return open(FCM_TOKEN_FILE).read().strip()
    except Exception:
        return ""


def send_fcm_with_token(token: str, keywords, transcript, scam_type,
                        caller_number="", confidence=0.9):
    """Send scam_alert FCM to a specific device token (multi-tenant aware)."""
    from firebase_admin import messaging as fcm_msg
    try:
        if not token:
            log.warning("BG: no FCM token — alert not sent")
            return
        msg = fcm_msg.Message(
            data={
                "type":          "scam_alert",
                "keywords":      ", ".join(keywords),
                "transcript":    transcript[:200],
                "scam_type":     scam_type,
                "caller_number": caller_number,
                "confidence":    str(confidence),
            },
            android=fcm_msg.AndroidConfig(priority="high"),
            token=token,
        )
        fcm_msg.send(msg)
        log.info(f"BG FCM sent ({scam_type}) to token ...{token[-10:]}")
    except Exception as e:
        log.error(f"BG FCM error: {e}")

import numpy as np
from vosk import Model, KaldiRecognizer
from faster_whisper import WhisperModel

MODEL_PATH_EN       = "/opt/vocaguard/model-en"
SAMPLE_RATE         = 16000
WHISPER_CHUNK_BYTES = 16000 * 2 * 4   # 4-second chunks at 16 kHz
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
        log.error("Usage: scam_detector_bg.py <audio_fifo> <caller_number> [user_phone]")
        sys.exit(1)

    audio_path    = sys.argv[1]
    caller_number = sys.argv[2]
    user_phone    = sys.argv[3] if len(sys.argv) > 3 else ""
    fcm_token     = get_fcm_token_for_number(user_phone)

    log.info(f"BG detector started: fifo={audio_path} caller={caller_number} user={user_phone}")

    # Open FIFO in a background thread so Vosk/Whisper model loading runs in
    # parallel.  Without this, model loading (~40s) blocks MixMonitor's write(),
    # starving Asterisk's audio pipeline and causing poor call quality + RTP
    # timeouts that drop the bridge.
    import threading
    fifo_holder = [None]
    fifo_ready  = threading.Event()

    def _open_fifo():
        try:
            fifo_holder[0] = open(audio_path, "rb")
        except Exception as e:
            log.error(f"BG: FIFO open error: {e}")
        finally:
            fifo_ready.set()

    threading.Thread(target=_open_fifo, daemon=True).start()

    try:
        rec_en = KaldiRecognizer(Model(MODEL_PATH_EN), SAMPLE_RATE)
        log.info("BG: Vosk model loaded")
    except Exception as e:
        log.error(f"BG: Vosk load error: {e}")
        return

    whisper_model = None
    try:
        whisper_model = WhisperModel("small", device="cpu", compute_type="int8")
        log.info("BG: Whisper model loaded")
    except Exception as e:
        log.warning(f"BG: Whisper load error: {e}")

    fifo_ready.wait(timeout=300)
    if fifo_holder[0] is None:
        log.error("BG: FIFO never connected — aborting")
        return

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
        is_scam, all_signals, total_score, mode = detect_scam_combined(get_window_text())
        if is_scam:
            conf = score_to_confidence(total_score)
            scam_type = classify_scam_type(all_signals)
            log.warning(f"BG SCAM ({label}): {scam_type} mode={mode} score={total_score} signals={all_signals}")
            send_fcm_with_token(fcm_token, all_signals, transcript_disp.strip(),
                                scam_type, caller_number, conf)
            alerted = True

    # Run Whisper in a background thread so it never blocks the FIFO read loop.
    # Without this, transcribing a 4s chunk takes several seconds on CPU, the
    # FIFO buffer fills up, MixMonitor's write() stalls, and Asterisk pauses
    # the audio pipeline — causing brief disconnections the user can hear.
    import queue as _queue
    whisper_q = _queue.Queue(maxsize=1)  # drop chunks if Whisper can't keep up

    def _whisper_worker():
        while True:
            item = whisper_q.get()
            if item is None:
                break
            buf_bytes = item
            try:
                audio_np = (
                    np.frombuffer(buf_bytes, dtype=np.int16)
                      .astype(np.float32) / 32768.0
                )
                segments, info = whisper_model.transcribe(
                    audio_np, beam_size=5, best_of=5,
                    vad_filter=False, language="en",
                )
                text = " ".join(seg.text for seg in segments).strip()
                if text:
                    add_text(text, f"ML({info.language})")
                    check_and_alert("whisper-ml")
            except Exception as e:
                log.warning(f"BG Whisper thread error: {e}")
            finally:
                whisper_q.task_done()

    if whisper_model:
        _wt = threading.Thread(target=_whisper_worker, daemon=True)
        _wt.start()

    try:
        audio_file = fifo_holder[0]
        # Enlarge pipe buffer to 1 MB so MixMonitor never stalls even if
        # Vosk is briefly busy — Linux default is only 64 KB.
        try:
            import fcntl
            fcntl.fcntl(audio_file.fileno(), 1031, 1024 * 1024)
        except Exception:
            pass
        with audio_file:
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
                else:
                    # Check partial result mid-utterance — fires before silence
                    partial = json.loads(rec_en.PartialResult()).get("partial", "").strip()
                    if partial and len(partial.split()) >= 4:
                        is_scam, all_signals, total_score, mode = detect_scam_combined(
                            partial + " " + get_window_text()
                        )
                        if is_scam and not alerted:
                            conf = score_to_confidence(total_score)
                            scam_type = classify_scam_type(all_signals)
                            log.warning(f"BG SCAM (vosk-partial): {scam_type} mode={mode} score={total_score}")
                            send_fcm_with_token(fcm_token, all_signals, partial,
                                                scam_type, caller_number, conf)
                            alerted = True

                whisper_buf.extend(resampled)
                if len(whisper_buf) >= WHISPER_CHUNK_BYTES:
                    if whisper_model:
                        try:
                            whisper_q.put_nowait(bytes(whisper_buf))
                        except _queue.Full:
                            log.debug("BG: Whisper busy, dropping chunk")
                    whisper_buf.clear()

    finally:
        if whisper_model:
            try:
                whisper_q.put_nowait(None)  # signal worker to stop
            except _queue.Full:
                pass

    except Exception as e:
        log.info(f"BG audio closed: {e}")
        if whisper_model:
            try:
                whisper_q.put_nowait(None)
            except Exception:
                pass

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
                audio_np, language="en", beam_size=5, best_of=5, vad_filter=False,
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
