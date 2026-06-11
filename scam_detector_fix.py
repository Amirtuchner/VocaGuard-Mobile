#!/opt/vocaguard-venv/bin/python3
"""
VocaGuard server-side scam detector — Asterisk EAGI script (v2)

Improvements over v1:
  - Russian + Arabic keyword tables added
  - Weighted keyword scoring (high-signal words = 2 pts, medium = 1 pt)
    → threshold 3 pts instead of naive count >= 2
  - Scam type classification sent in FCM payload
    (Android app no longer has to guess type from raw keywords)
  - Whisper upgraded tiny → base for better Hebrew/Russian/Arabic accuracy
  - Whisper chunk reduced 5 s → 3 s for faster early detection
  - Whisper language auto-detection (was hard-coded Hebrew)
    → handles mixed-language calls and Russian/Arabic speakers
"""
import sys, os, json, logging, audioop
import numpy as np
from vosk import Model, KaldiRecognizer
from faster_whisper import WhisperModel
import firebase_admin
from firebase_admin import credentials, messaging

MODEL_PATH_EN        = "/opt/vocaguard/model-en"
SERVICE_ACCOUNT      = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE       = "/opt/vocaguard/fcm_token.txt"
SAMPLE_RATE          = 16000
WHISPER_CHUNK_BYTES  = 16000 * 2 * 3   # 3-second chunks (was 5 s)
SCAM_SCORE_THRESHOLD = 3               # minimum weighted score to fire an alert

logging.basicConfig(
    filename="/var/log/vocaguard_agi.log",
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s"
)
log = logging.getLogger("vocaguard")

# ---------------------------------------------------------------------------
# Keyword tables — (keyword, weight)
# weight 2 = high-signal (strongly correlated with scams, rarely in legit calls)
# weight 1 = medium-signal (context-dependent, needs a partner keyword to fire)
# ---------------------------------------------------------------------------

SCAM_KEYWORDS_EN = [
    # High-signal
    ("arrest warrant", 2), ("federal investigation", 2), ("safe account", 2),
    ("do not tell anyone", 2), ("keep this confidential", 2),
    ("gift card", 2), ("wire transfer", 2), ("western union", 2),
    ("social security", 2), ("remote access", 2), ("anydesk", 2), ("teamviewer", 2),
    ("bitcoin", 2), ("crypto", 2), ("money mule", 2),
    # Medium-signal
    ("irs", 1), ("tax", 1), ("legal action", 1), ("deportation", 1),
    ("virus", 1), ("microsoft", 1), ("computer infected", 1), ("tech support", 1),
    ("suspicious activity", 1), ("account frozen", 1), ("verify your account", 1),
    ("bank transfer", 1), ("account compromised", 1),
    ("won", 1), ("lottery", 1), ("prize", 1), ("congratulations", 1),
    ("ssn", 1), ("suspended", 1),
    ("press 1", 1), ("press one", 1), ("do not hang up", 1),
    ("password", 1), ("click the link", 1),
    ("send money", 1), ("bail money", 1),
    ("package", 1), ("customs fee", 1), ("fedex", 1), ("dhl", 1), ("ups", 1),
    ("job offer", 1), ("work from home", 1), ("hiring", 1), ("training fee", 1),
    ("urgent", 1), ("immediately", 1), ("right now", 1), ("last chance", 1),
    ("fraud department", 1),
]

SCAM_KEYWORDS_HE = [
    # High-signal
    ("צו מעצר", 2), ("חשבון בטוח", 2), ("אל תספר", 2), ("בסוד", 2),
    ("מחלקת הונאות", 2), ("כרטיס מתנה", 2), ("העברה בנקאית", 2),
    ("ביטקוין", 2), ("קריפטו", 2), ("גישה מרחוק", 2), ("anydesk", 2), ("teamviewer", 2),
    ("מספר תעודת זהות", 2),
    # Medium-signal
    ("דחוף", 1), ("מיד", 1), ("עכשיו", 1), ("בדחיפות", 1),
    ("חסום", 1), ("הוקפא", 1), ("מושעה", 1), ("חשבון מוקפא", 1),
    ("קוד אימות", 1), ("קוד otp", 1), ("אימות", 1),
    ("העברת כסף", 1), ("ביט", 1), ("פייבוקס", 1), ("פרטי בנק", 1),
    ("מס הכנסה", 1), ("רשות המסים", 1), ("חוב מס", 1), ("עיקול מס", 1),
    ("תיק פלילי", 1), ("הוצאה לפועל", 1), ("עיקול", 1), ("חקירה", 1),
    ("וירוס", 1), ("תמיכה טכנית", 1),
    ("זכית", 1), ("הגרלה", 1), ("זכייה", 1), ("מזל טוב", 1),
    ("לחץ אחת", 1), ("הודעה מוקלטת", 1), ("הודעה אוטומטית", 1),
    ("חבילה", 1), ("אגרת מכס", 1),
    ("הצעת עבודה", 1), ("עמלה", 1),
    ("תרומה", 1), ("תעודת זהות", 1),
]

SCAM_KEYWORDS_RU = [
    # High-signal
    ("ордер на арест", 2), ("федеральное расследование", 2), ("безопасный счет", 2),
    ("никому не говорите", 2), ("конфиденциально", 2),
    ("подарочная карта", 2), ("банковский перевод", 2), ("биткоин", 2),
    ("криптовалюта", 2), ("удаленный доступ", 2), ("anydesk", 2), ("teamviewer", 2),
    ("снилс", 2), ("паспортные данные", 2),
    # Medium-signal
    ("срочно", 1), ("немедленно", 1), ("прямо сейчас", 1),
    ("заблокирован", 1), ("заморожен", 1), ("приостановлен", 1),
    ("код подтверждения", 1), ("подтвердить", 1), ("верифицировать", 1),
    ("перевод денег", 1), ("налоговая служба", 1), ("налоговый долг", 1), ("фнс", 1),
    ("уголовное дело", 1), ("расследование", 1),
    ("вирус", 1), ("техподдержка", 1),
    ("выиграли", 1), ("лотерея", 1), ("приз", 1),
    ("паспорт", 1), ("инн", 1),
]

SCAM_KEYWORDS_AR = [
    # High-signal
    ("أمر اعتقال", 2), ("تحقيق فيدرالي", 2), ("حساب آمن", 2),
    ("لا تخبر أحدا", 2), ("سري للغاية", 2), ("قسم الاحتيال", 2),
    ("بطاقة هدية", 2), ("تحويل بنكي", 2), ("بيتكوين", 2), ("عملة مشفرة", 2),
    ("وصول عن بعد", 2), ("anydesk", 2), ("teamviewer", 2),
    # Medium-signal
    ("عاجل", 1), ("فورا", 1), ("الآن فورا", 1),
    ("محظور", 1), ("مجمد", 1), ("معلق", 1),
    ("رمز التحقق", 1), ("التحقق", 1), ("تأكيد الهوية", 1),
    ("تحويل مالي", 1), ("حوالة مالية", 1),
    ("مصلحة الضرائب", 1), ("دين ضريبي", 1),
    ("قضية جنائية", 1), ("تحقيق جنائي", 1),
    ("فيروس", 1), ("دعم فني", 1),
    ("فزت", 1), ("جائزة", 1), ("يانصيب", 1),
    ("رقم الهوية", 1), ("بطاقة هوية", 1),
]

ALL_KEYWORD_TABLES = [
    SCAM_KEYWORDS_EN,
    SCAM_KEYWORDS_HE,
    SCAM_KEYWORDS_RU,
    SCAM_KEYWORDS_AR,
]

# ---------------------------------------------------------------------------
# Scam type classification
# ---------------------------------------------------------------------------

_TYPE_SIGNALS = [
    ("IRS_SCAM", [
        "irs", "tax", "arrest warrant", "federal investigation",
        "מס הכנסה", "צו מעצר", "חקירה",
        "налоговая служба", "налоговый долг", "ордер на арест",
        "مصلحة الضرائب", "دين ضريبي", "أمر اعتقال",
    ]),
    ("TECH_SUPPORT", [
        "virus", "microsoft", "tech support", "remote access", "anydesk", "teamviewer",
        "computer infected",
        "וירוס", "גישה מרחוק", "תמיכה טכנית",
        "вирус", "удаленный доступ", "техподдержка",
        "فيروس", "وصول عن بعد", "دعم فني",
    ]),
    ("BANK_FRAUD", [
        "bank transfer", "account frozen", "safe account", "suspicious activity",
        "העברה בנקאית", "חשבון מוקפא", "חשבון בטוח",
        "банковский перевод", "безопасный счет",
        "تحويل بنكي", "حساب آمن",
    ]),
    ("SOCIAL_SECURITY", [
        "social security", "ssn",
        "תעודת זהות", "מספר תעודת זהות",
        "снилс", "паспортные данные",
        "رقم الهوية", "بطاقة هوية",
    ]),
    ("LOTTERY_PRIZE", [
        "lottery", "prize", "won", "congratulations",
        "הגרלה", "זכייה", "זכית",
        "лотерея", "выиграли", "приз",
        "يانصيب", "فزت", "جائزة",
    ]),
    ("ROBOCALL", [
        "press 1", "press one", "do not hang up",
        "לחץ אחת", "הודעה מוקלטת", "הודעה אוטומטית",
    ]),
    ("PHISHING", [
        "password", "verify your account", "account compromised", "click the link",
        "קוד אימות", "אימות",
        "код подтверждения", "подтвердить",
        "رمز التحقق", "التحقق",
    ]),
    ("DELIVERY_SCAM", [
        "package", "customs fee", "fedex", "dhl", "ups",
        "חבילה", "אגרת מכס",
    ]),
    ("JOB_SCAM", [
        "job offer", "work from home", "training fee", "money mule",
        "הצעת עבודה", "עמלה",
    ]),
    ("DONATION_FRAUD", [
        "donation", "donate",
        "תרומה",
    ]),
]

def classify_scam_type(matched_keywords):
    joined = " ".join(matched_keywords).lower()
    for scam_type, signals in _TYPE_SIGNALS:
        if any(sig in joined for sig in signals):
            return scam_type
    return "UNKNOWN"

# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------

def detect_scam(text):
    """
    Scan text against all four language keyword tables.
    Returns (is_scam, matched_keywords, confidence).

    Two conditions must BOTH be true to fire an alert:
      1. Total weighted score >= SCAM_SCORE_THRESHOLD (3 pts)
      2. At least one HIGH-SIGNAL keyword (weight=2) is present
         → prevents three generic medium-signal words like
           "urgent" + "tax" + "package" from triggering a false positive
    """
    text_lower = text.lower()
    score = 0
    matched = []
    has_high_signal = False
    for table in ALL_KEYWORD_TABLES:
        for kw, weight in table:
            if kw in text_lower and kw not in matched:
                score += weight
                matched.append(kw)
                if weight >= 2:
                    has_high_signal = True
    if score >= SCAM_SCORE_THRESHOLD and has_high_signal:
        confidence = min(0.99, 0.5 + 0.04 * score)
        return True, matched[:5], confidence
    return False, [], 0.0

# ---------------------------------------------------------------------------
# Firebase
# ---------------------------------------------------------------------------

try:
    cred = credentials.Certificate(SERVICE_ACCOUNT)
    firebase_admin.initialize_app(cred)
    log.info("Firebase Admin SDK initialized")
except Exception as e:
    log.error(f"Firebase init error: {e}")

def send_fcm_alert(keywords, transcript, scam_type, caller_number="", confidence=0.9):
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
                "type":          "scam_alert",
                "keywords":      ", ".join(keywords),
                "transcript":    transcript[:200],
                "scam_type":     scam_type,
                "caller_number": caller_number,
                "confidence":    str(round(confidence, 2)),
            },
            android=messaging.AndroidConfig(priority="high"),
            token=token,
        )
        response = messaging.send(message)
        log.info(f"FCM sent ({scam_type}): {response}")
    except Exception as e:
        log.error(f"FCM error: {e}")

# ---------------------------------------------------------------------------
# AGI helpers
# ---------------------------------------------------------------------------

def agi_send(cmd):
    sys.stdout.write(cmd + "\n")
    sys.stdout.flush()

def agi_init():
    agi_vars = {}
    while True:
        line = sys.stdin.readline()
        if line.strip() == "":
            break
        if ":" in line:
            key, _, value = line.partition(":")
            agi_vars[key.strip()] = value.strip()
    return agi_vars

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    agi_vars = agi_init()
    caller_number = agi_vars.get("agi_callerid", "")
    log.info(f"Call from: {caller_number}")
    # No agi_recv() — avoids blocking on Asterisk's VERBOSE response
    agi_send('VERBOSE "VocaGuard scam detector v2 started" 1')

    # English: Vosk streaming (low latency)
    try:
        model_en = Model(MODEL_PATH_EN)
        rec_en = KaldiRecognizer(model_en, SAMPLE_RATE)
        log.info("English Vosk model loaded")
    except Exception as e:
        log.error(f"Vosk model load error: {e}")
        return

    # Multi-language: Whisper base with auto language detection
    # base is significantly more accurate than tiny for Hebrew/Russian/Arabic
    whisper_model = None
    try:
        whisper_model = WhisperModel("base", device="cpu", compute_type="int8")
        log.info("Whisper base model loaded (auto language detection)")
    except Exception as e:
        log.warning(f"Whisper load error — non-English detection disabled: {e}")

    audio_fd      = 3
    transcript_en = ""
    transcript_ml = ""   # Hebrew / Russian / Arabic / other via Whisper
    alerted       = False
    resample_state = None
    whisper_buf   = bytearray()

    def run_whisper(audio_bytes):
        """Transcribe a PCM buffer with Whisper using automatic language detection."""
        nonlocal transcript_ml
        if whisper_model is None or not audio_bytes:
            return
        try:
            audio_np = (
                np.frombuffer(bytes(audio_bytes), dtype=np.int16)
                  .astype(np.float32) / 32768.0
            )
            segments, info = whisper_model.transcribe(
                audio_np,
                language=None,      # auto-detect: handles HE, RU, AR, mixed
                beam_size=1,
                best_of=1,
                vad_filter=True,
            )
            text = " ".join(seg.text for seg in segments).strip()
            if text:
                log.info(f"STT-ML ({info.language}): {text}")
                transcript_ml += " " + text
        except Exception as e:
            log.warning(f"Whisper error: {e}")

    def check_and_alert(label):
        nonlocal alerted
        if alerted:
            return
        combined_text = transcript_en + " " + transcript_ml
        is_scam, matched_kws, conf = detect_scam(combined_text)
        if is_scam:
            scam_type = classify_scam_type(matched_kws)
            log.warning(
                f"SCAM DETECTED ({label}): type={scam_type} "
                f"keywords={matched_kws} confidence={conf:.2f}"
            )
            display_transcript = transcript_ml.strip() or transcript_en.strip()
            send_fcm_alert(matched_kws, display_transcript, scam_type, caller_number, conf)
            alerted = True

    log.info("Audio analysis started")
    try:
        while True:
            data = os.read(audio_fd, 3200)
            if not data:
                log.info("Audio stream ended")
                break

            # EAGI delivers 8 kHz SLIN; resample to 16 kHz for Vosk / Whisper
            resampled, resample_state = audioop.ratecv(
                data, 2, 1, 8000, 16000, resample_state
            )

            # English: Vosk streaming (result on sentence boundary)
            if rec_en.AcceptWaveform(resampled):
                text = json.loads(rec_en.Result()).get("text", "").strip()
                if text:
                    log.info(f"STT-EN: {text}")
                    transcript_en += " " + text
                    check_and_alert("vosk-en")

            # Multi-language: Whisper every 3 seconds
            whisper_buf.extend(resampled)
            if len(whisper_buf) >= WHISPER_CHUNK_BYTES:
                run_whisper(bytes(whisper_buf))
                whisper_buf.clear()
                check_and_alert("whisper-ml")

    except OSError as e:
        log.info(f"Audio fd closed: {e}")
    except Exception as e:
        log.error(f"Unexpected error: {e}")

    # Final flush
    final_en = json.loads(rec_en.FinalResult()).get("text", "").strip()
    if final_en:
        log.info(f"STT-EN final: {final_en}")
        transcript_en += " " + final_en

    if whisper_buf:
        run_whisper(bytes(whisper_buf))

    check_and_alert("final")

    log.info(
        f"Call ended. EN: {transcript_en.strip()!r} | ML: {transcript_ml.strip()!r}"
    )


if __name__ == "__main__":
    main()
