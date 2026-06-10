#!/opt/vocaguard-venv/bin/python3
import sys, os, json, logging, audioop
import numpy as np
from vosk import Model, KaldiRecognizer
from faster_whisper import WhisperModel
import firebase_admin
from firebase_admin import credentials, messaging

MODEL_PATH_EN        = "/opt/vocaguard/model-en"
SERVICE_ACCOUNT      = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE       = "/opt/vocaguard/fcm_token.txt"
SAMPLE_RATE          = 16000  # model is 16kHz; we resample from 8kHz
# 5 seconds of 16kHz 16-bit mono = 16000 * 2 * 5 bytes
WHISPER_CHUNK_BYTES  = 16000 * 2 * 5

SCAM_KEYWORDS = [
    "irs","tax","arrest warrant","legal action","deportation",
    "virus","microsoft","computer infected","tech support","remote access",
    "suspicious activity","account frozen","verify your account","bank transfer",
    "won","lottery","prize","claim your","congratulations",
    "social security","ssn","suspended",
    "press 1","press one","do not hang up",
    "password","click the link","account compromised",
    "send money","western union","bail money",
    "package","customs fee","fedex","dhl","ups",
    "job offer","work from home","hiring","training fee","money mule",
    "gift card","wire transfer","bitcoin","crypto",
    "urgent","immediately","right now","last chance",
    "safe account","fraud department","federal investigation",
    "do not tell anyone","keep this confidential",
]

SCAM_KEYWORDS_HE = [
    # Urgency
    "דחוף", "מיד", "עכשיו", "בדחיפות", "ללא דיחוי",
    # Blocked account
    "חסום", "הוקפא", "מושעה", "נחסם", "חשבון מוקפא",
    # Verify / OTP
    "קוד אימות", "קוד otp", "לאמת", "לאשר", "אימות",
    # Money / payment
    "העברת כסף", "העברה בנקאית", "ביט", "פייבוקס", "כרטיס אשראי", "פרטי בנק",
    # Tax / IRS
    "מס הכנסה", "רשות המסים", "חוב מס", "עיקול מס",
    # Arrest / legal
    "צו מעצר", "תיק פלילי", "הוצאה לפועל", "עיקול", "חקירה",
    # Tech support
    "גישה מרחוק", "anydesk", "teamviewer", "וירוס", "תמיכה טכנית",
    # Lottery / prize
    "זכית", "הגרלה", "זכייה", "מזל טוב",
    # Identity
    "תעודת זהות", "מספר תעודת זהות",
    # Robocall
    "לחץ אחת", "הודעה מוקלטת", "הודעה אוטומטית",
    # Gift card / crypto
    "כרטיס מתנה", "ביטקוין", "קריפטו",
    # Confidentiality / pressure
    "אל תספר", "בסוד", "חשבון בטוח", "מחלקת הונאות",
    # Delivery
    "חבילה", "אגרת מכס",
    # Job scam
    "הצעת עבודה", "עמלה",
    # Donation fraud
    "תרומה", "לתרום",
]

logging.basicConfig(
    filename="/var/log/vocaguard_agi.log",
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s"
)
log = logging.getLogger("vocaguard")

try:
    cred = credentials.Certificate(SERVICE_ACCOUNT)
    firebase_admin.initialize_app(cred)
    log.info("Firebase Admin SDK initialized")
except Exception as e:
    log.error(f"Firebase init error: {e}")

def agi_send(cmd):
    sys.stdout.write(cmd + "\n")
    sys.stdout.flush()

def agi_init():
    agi_vars = {}
    while True:
        line = sys.stdin.readline()
        if line.strip() == "": break
        if ":" in line:
            key, _, value = line.partition(":")
            agi_vars[key.strip()] = value.strip()
    return agi_vars

def detect_scam(text, keywords=None):
    if keywords is None:
        keywords = SCAM_KEYWORDS
    text_lower = text.lower()
    matched = [kw for kw in keywords if kw in text_lower]
    if len(matched) >= 2:
        confidence = min(0.99, 0.5 + 0.1 * len(matched))
        return True, ", ".join(matched[:3]), confidence
    return False, "", 0.0

def send_fcm_alert(keywords, transcript, caller_number="", confidence=0.9):
    try:
        if not os.path.exists(FCM_TOKEN_FILE):
            log.warning("No FCM token file found")
            return
        with open(FCM_TOKEN_FILE) as f:
            token = f.read().strip()
        if not token:
            log.warning("FCM token is empty")
            return
        message = messaging.Message(
            data={
                "type": "scam_alert",
                "keywords": keywords,
                "transcript": transcript[:200],
                "caller_number": caller_number,
                "confidence": str(round(confidence, 2)),
            },
            android=messaging.AndroidConfig(priority="high"),
            token=token,
        )
        response = messaging.send(message)
        log.info(f"FCM sent: {response}")
    except Exception as e:
        log.error(f"FCM error: {e}")

def main():
    agi_vars = agi_init()
    caller_number = agi_vars.get("agi_callerid", "")
    log.info(f"Call from: {caller_number}")
    # No agi_recv() — don't block waiting for Asterisk's VERBOSE response
    agi_send('VERBOSE "VocaGuard scam detector started" 1')

    try:
        model_en = Model(MODEL_PATH_EN)
        rec_en = KaldiRecognizer(model_en, SAMPLE_RATE)
        log.info("English Vosk model loaded")
    except Exception as e:
        log.error(f"English model load error: {e}")
        return

    try:
        whisper_model = WhisperModel("tiny", device="cpu", compute_type="int8")
        log.info("Hebrew Whisper model loaded")
    except Exception as e:
        log.warning(f"Whisper load error (Hebrew detection disabled): {e}")
        whisper_model = None

    audio_fd = 3
    transcript_en = ""
    transcript_he = ""
    alerted = False
    resample_state = None
    # Buffer for 5-second Whisper chunks (16kHz, 16-bit, mono)
    whisper_buf = bytearray()
    log.info("Audio analysis started")

    def run_whisper_hebrew(audio_bytes):
        """Transcribe a PCM buffer with Whisper, forced to Hebrew."""
        nonlocal transcript_he
        if whisper_model is None or not audio_bytes:
            return
        try:
            audio_np = np.frombuffer(bytes(audio_bytes), dtype=np.int16).astype(np.float32) / 32768.0
            segments, _ = whisper_model.transcribe(
                audio_np, language="he", beam_size=1, best_of=1, vad_filter=True
            )
            text = " ".join(seg.text for seg in segments).strip()
            if text:
                log.info(f"STT-HE: {text}")
                transcript_he += " " + text
        except Exception as e:
            log.warning(f"Whisper error: {e}")

    def check_and_alert(label):
        nonlocal alerted
        if alerted:
            return
        is_scam, kw, conf = detect_scam(transcript_en)
        if not is_scam:
            is_scam, kw, conf = detect_scam(transcript_he, SCAM_KEYWORDS_HE)
        if is_scam:
            log.warning(f"SCAM DETECTED ({label}): {kw} (confidence={conf:.2f})")
            combined = (transcript_he.strip() or transcript_en.strip())
            send_fcm_alert(kw, combined, caller_number, conf)
            alerted = True

    try:
        while True:
            data = os.read(audio_fd, 3200)
            if not data:
                log.info("Audio stream ended")
                break
            # EAGI sends 8kHz slin; resample to 16kHz for Vosk / Whisper
            resampled, resample_state = audioop.ratecv(
                data, 2, 1, 8000, 16000, resample_state
            )
            # English: Vosk streaming
            if rec_en.AcceptWaveform(resampled):
                text = json.loads(rec_en.Result()).get("text", "").strip()
                if text:
                    log.info(f"STT-EN: {text}")
                    transcript_en += " " + text
                    check_and_alert("vosk-en")
            # Hebrew: buffer → Whisper every 5 seconds
            whisper_buf.extend(resampled)
            if len(whisper_buf) >= WHISPER_CHUNK_BYTES:
                run_whisper_hebrew(whisper_buf)
                whisper_buf.clear()
                check_and_alert("whisper-he")
    except OSError as e:
        log.info(f"Audio fd closed: {e}")
    except Exception as e:
        log.error(f"Unexpected error: {e}")

    # Final flush
    final_en = json.loads(rec_en.FinalResult()).get("text", "").strip()
    if final_en:
        log.info(f"STT-EN final: {final_en}")
        transcript_en += " " + final_en
    run_whisper_hebrew(whisper_buf)  # remaining audio
    check_and_alert("final")

    log.info(f"Call ended. EN: {transcript_en.strip()} | HE: {transcript_he.strip()}")

if __name__ == "__main__":
    main()
