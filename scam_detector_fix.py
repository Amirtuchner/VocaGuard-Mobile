#!/opt/vocaguard-venv/bin/python3
import sys, os, json, logging, audioop
from vosk import Model, KaldiRecognizer
import firebase_admin
from firebase_admin import credentials, messaging

MODEL_PATH      = "/opt/vocaguard/model-en"
SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"
SAMPLE_RATE     = 16000  # model is 16kHz; we resample from 8kHz

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
    while True:
        line = sys.stdin.readline()
        if line.strip() == "": break

def detect_scam(text):
    text_lower = text.lower()
    matched = [kw for kw in SCAM_KEYWORDS if kw in text_lower]
    return (True, ", ".join(matched[:3])) if len(matched) >= 2 else (False, "")

def send_fcm_alert(keywords, transcript):
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
            data={"type": "scam_alert", "keywords": keywords,
                  "transcript": transcript[:200]},
            android=messaging.AndroidConfig(priority="high"),
            token=token,
        )
        response = messaging.send(message)
        log.info(f"FCM sent: {response}")
    except Exception as e:
        log.error(f"FCM error: {e}")

def main():
    agi_init()
    # No agi_recv() — don't block waiting for Asterisk's VERBOSE response
    agi_send('VERBOSE "VocaGuard scam detector started" 1')

    try:
        model = Model(MODEL_PATH)
        recognizer = KaldiRecognizer(model, SAMPLE_RATE)
        log.info("Vosk model loaded")
    except Exception as e:
        log.error(f"Vosk load error: {e}")
        return

    audio_fd = 3
    full_transcript = ""
    alerted = False
    resample_state = None
    log.info("Audio analysis started")

    try:
        while True:
            data = os.read(audio_fd, 3200)
            if not data:
                log.info("Audio stream ended")
                break
            # EAGI sends 8kHz slin; resample to 16kHz for the Vosk model
            resampled, resample_state = audioop.ratecv(
                data, 2, 1, 8000, 16000, resample_state
            )
            if recognizer.AcceptWaveform(resampled):
                text = json.loads(recognizer.Result()).get("text", "").strip()
                if text:
                    log.info(f"STT: {text}")
                    full_transcript += " " + text
                    if not alerted:
                        is_scam, keywords = detect_scam(full_transcript)
                        if is_scam:
                            log.warning(f"SCAM DETECTED: {keywords}")
                            send_fcm_alert(keywords, full_transcript.strip())
                            alerted = True
    except OSError as e:
        log.info(f"Audio fd closed: {e}")
    except Exception as e:
        log.error(f"Unexpected error: {e}")

    final = json.loads(recognizer.FinalResult()).get("text", "").strip()
    if final:
        log.info(f"STT final: {final}")
        full_transcript += " " + final
        if not alerted:
            is_scam, keywords = detect_scam(full_transcript)
            if is_scam:
                log.warning(f"SCAM DETECTED (final): {keywords}")
                send_fcm_alert(keywords, full_transcript.strip())

    log.info(f"Call ended. Transcript: {full_transcript.strip()}")

if __name__ == "__main__":
    main()
