#!/opt/vocaguard-venv/bin/python3
"""
VocaGuard server-side scam detector — Asterisk EAGI script (v3)

Changes over v2:
  - Whisper chunk reduced 3s → 2s (no Hebrew Vosk model exists; shorter chunks
    reduce worst-case Hebrew/Russian/Arabic detection latency from 6s to 4s)
  - Hebrew keyword table expanded: ביטוח לאומי, Israeli bank names,
    police/arrest phrases, OTP, power-of-attorney, remote-app-install
  - Confidence scoring changed from linear to sigmoid calibration:
      score=3 → 0.56,  score=5 → 0.70,  score=8 → 0.89,  score=12 → 0.98
  - Sliding 90s window: transcript text older than 90s no longer contributes
    to the score, so a long legitimate call after an early false keyword
    cannot accumulate to a false positive
"""
import sys, os, json, logging, audioop, math, time, re
from collections import deque

# Normalises text for whole-word keyword matching:
# replaces any non-alphanumeric/non-space char with a space (handles punctuation,
# Hebrew geresh/gershayim, ASCII quotes) so "ביטוח" never matches keyword "ביט".
_WORD_SEP = re.compile(r"[^\w\s]", re.UNICODE)

# Hebrew single-letter grammatical prefixes that attach directly to the
# following word without a space (ב=in/at, מ=from, ל=to, ה=the, ו=and,
# כ=as, ש=that, ד=of). A keyword may appear in speech as "מהבנק הפועלים"
# instead of "הבנק הפועלים" — the prefix-aware check handles this.
_HE_PREFIXES = "במלהוכשד"
import numpy as np
from vosk import Model, KaldiRecognizer
from faster_whisper import WhisperModel
import firebase_admin
from firebase_admin import credentials, messaging

MODEL_PATH_EN        = "/opt/vocaguard/model-en"
SERVICE_ACCOUNT      = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE       = "/opt/vocaguard/fcm_token.txt"
SAMPLE_RATE          = 16000
WHISPER_CHUNK_BYTES  = 16000 * 2 * 2   # 2-second chunks (was 3 s)
SCAM_SCORE_THRESHOLD = 3
WINDOW_SECONDS       = 90              # sliding window: only last 90 s counts

logging.basicConfig(
    filename="/var/log/vocaguard_agi.log",
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s"
)
log = logging.getLogger("vocaguard")

# ---------------------------------------------------------------------------
# Confidence calibration
# ---------------------------------------------------------------------------

def score_to_confidence(score: int) -> float:
    """Sigmoid: score=3→0.56, score=5→0.70, score=8→0.89, score=12→0.98"""
    sigmoid = 1.0 / (1.0 + math.exp(-0.5 * (score - 5)))
    return round(min(0.99, 0.4 + 0.6 * sigmoid), 2)

# ---------------------------------------------------------------------------
# Keyword tables — (keyword, weight)
# weight 2 = high-signal (strongly correlated with scams)
# weight 1 = medium-signal (needs a partner keyword)
# ---------------------------------------------------------------------------

SCAM_KEYWORDS_EN = [
    ("arrest warrant", 2), ("federal investigation", 2), ("safe account", 2),
    ("do not tell anyone", 2), ("keep this confidential", 2),
    ("gift card", 2), ("wire transfer", 2), ("western union", 2),
    ("social security", 2), ("remote access", 2), ("anydesk", 2), ("teamviewer", 2),
    ("bitcoin", 2), ("crypto", 2), ("money mule", 2),
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
    # ── High-signal (existing) ──
    ("צו מעצר", 2), ("חשבון בטוח", 2), ("אל תספר", 2), ("בסוד", 2),
    ("מחלקת הונאות", 2), ("כרטיס מתנה", 2), ("העברה בנקאית", 2),
    ("ביטקוין", 2), ("קריפטו", 2), ("גישה מרחוק", 2), ("anydesk", 2), ("teamviewer", 2),
    ("מספר תעודת זהות", 2),
    # ── High-signal (new — Israeli-specific) ──
    ("ביטוח לאומי", 2),          # National Insurance Institute — top scam vector in IL
    ("המוסד לביטוח לאומי", 2),
    ("קצבת נכות", 2),            # disability benefit (used in NII phishing calls)
    ("שבס", 2),                   # Israel Prison Service (שב"ס) — arrest-warrant scams
                                  # (STT transcribes without the gershayim mark)
    ("צו עיכוב יציאה", 2),       # travel ban order
    ("תביעה משפטית", 2),         # lawsuit
    ("ייפוי כוח", 2),            # power of attorney — financial fraud
    ("קוד חד פעמי", 2),          # one-time code / OTP
    ("הקפאת חשבון", 2),          # account freeze
    ("הורד את האפליקציה", 2),    # install the app — remote-access scam entry point
    # ── Medium-signal (existing) ──
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
    # ── Medium-signal (new — Israeli-specific) ──
    ("משטרה", 1),                 # police (impersonation calls)
    ("שוטר", 1),                  # officer
    ("בנק הפועלים", 1),           # Israeli banks — callers claiming to be from the bank
    ("בנק לאומי", 1),
    ("דיסקונט", 1),
    ("מזרחי טפחות", 1),
    ("ישראכרט", 1),
    ("כרטיס אשראי", 1),          # credit card
    ("כאל", 1),                   # Israeli credit company (CAL)
    ("מקס", 1),                   # Max credit
    ("פפר פיי", 1),               # Pepper Pay
    ("קישור", 1),                 # link — phishing SMS/calls
    ("לינק", 1),
    ("אפליקציה", 1),              # app (often "download an app" = remote access)
    ("מסרון", 1),                 # SMS
    ("פרטים אישיים", 1),          # personal details
    ("מנהל חשבון", 1),           # account manager (impersonation)
    ("חתימה", 1),                 # signature
]

SCAM_KEYWORDS_RU = [
    ("ордер на арест", 2), ("федеральное расследование", 2), ("безопасный счет", 2),
    ("никому не говорите", 2), ("конфиденциально", 2),
    ("подарочная карта", 2), ("банковский перевод", 2), ("биткоин", 2),
    ("криптовалюта", 2), ("удаленный доступ", 2), ("anydesk", 2), ("teamviewer", 2),
    ("снилс", 2), ("паспортные данные", 2),
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
    ("أمر اعتقال", 2), ("تحقيق فيدرالي", 2), ("حساب آمن", 2),
    ("لا تخبر أحدا", 2), ("سري للغاية", 2), ("قسم الاحتيال", 2),
    ("بطاقة هدية", 2), ("تحويل بنكي", 2), ("بيتكوين", 2), ("عملة مشفرة", 2),
    ("وصول عن بعد", 2), ("anydesk", 2), ("teamviewer", 2),
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

ALL_KEYWORD_TABLES = [SCAM_KEYWORDS_EN, SCAM_KEYWORDS_HE, SCAM_KEYWORDS_RU, SCAM_KEYWORDS_AR]

# ---------------------------------------------------------------------------
# Scam type classification
# ---------------------------------------------------------------------------

_TYPE_SIGNALS = [
    ("IRS_SCAM", [
        "irs", "tax", "arrest warrant", "federal investigation",
        "מס הכנסה", "צו מעצר", "חקירה", "ביטוח לאומי", "המוסד לביטוח לאומי",
        "тналоговая служба", "налоговый долг", "ордер на арест",
        "مصلحة الضرائب", "دين ضريبي", "أمر اعتقال",
    ]),
    ("TECH_SUPPORT", [
        "virus", "microsoft", "tech support", "remote access", "anydesk", "teamviewer",
        "computer infected",
        "וירוס", "גישה מרחוק", "תמיכה טכנית", "הורד את האפליקציה", "אפליקציה",
        "вирус", "удаленный доступ", "техподдержка",
        "فيروس", "وصول عن بعد", "دعم فني",
    ]),
    ("BANK_FRAUD", [
        "bank transfer", "account frozen", "safe account", "suspicious activity",
        "העברה בנקאית", "חשבון מוקפא", "חשבון בטוח", "הקפאת חשבון",
        "בנק הפועלים", "בנק לאומי", "דיסקונט", "מזרחי טפחות",
        "банковский перевод", "безопасный счет",
        "تحويل بنكي", "حساب آمن",
    ]),
    ("SOCIAL_SECURITY", [
        "social security", "ssn",
        "תעודת זהות", "מספר תעודת זהות", "ביטוח לאומי", "קצבת נכות",
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
        "קוד אימות", "אימות", "קוד חד פעמי", "קוד otp", "קישור", "לינק",
        "код подтверждения", "подтвердить",
        "رمز التحقق", "التحقق",
    ]),
    ("ARREST_SCAM", [
        "שבס", "צו עיכוב יציאה", "תביעה משפטית", "ייפוי כוח",
        "arrest warrant", "federal investigation",
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

def classify_scam_type(matched_keywords: list) -> str:
    joined = " ".join(matched_keywords).lower()
    for scam_type, signals in _TYPE_SIGNALS:
        if any(sig in joined for sig in signals):
            return scam_type
    return "UNKNOWN"

# ---------------------------------------------------------------------------
# Detection — sliding window
# ---------------------------------------------------------------------------

def detect_scam(window_text: str):
    """
    Scan window_text against all keyword tables.
    Returns (is_scam, matched_keywords, score).

    Both conditions must hold to fire:
      1. Total weighted score >= SCAM_SCORE_THRESHOLD (3)
      2. At least one HIGH-SIGNAL keyword (weight >= 2) present

    Matching is whole-word: punctuation/gershayim are normalised to spaces so
    that e.g. "ביט" (payment app) does not match inside "ביטוח לאומי".
    """
    # Normalise: punctuation → space, pad with spaces for boundary matching
    normed_text = " " + _WORD_SEP.sub(" ", window_text.lower()) + " "
    score = 0
    matched = []
    has_high_signal = False
    for table in ALL_KEYWORD_TABLES:
        for kw, weight in table:
            kw_s = _WORD_SEP.sub(" ", kw.lower()).strip()  # normalised keyword, no padding
            # Standard word-boundary match
            found = f" {kw_s} " in normed_text
            # Hebrew prefix match: keyword may be preceded by a single
            # grammatical prefix letter (מבנק הפועלים, לביטוח לאומי, etc.)
            if not found:
                for pfx in _HE_PREFIXES:
                    if f" {pfx}{kw_s} " in normed_text:
                        found = True
                        break
            if found and kw not in matched:
                score += weight
                matched.append(kw)
                if weight >= 2:
                    has_high_signal = True
    if score >= SCAM_SCORE_THRESHOLD and has_high_signal:
        return True, matched[:5], score
    return False, [], 0

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
                "confidence":    str(confidence),
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
    agi_send('VERBOSE "VocaGuard scam detector v3 started" 1')

    # English Vosk — real-time streaming
    try:
        model_en = Model(MODEL_PATH_EN)
        rec_en = KaldiRecognizer(model_en, SAMPLE_RATE)
        log.info("English Vosk model loaded")
    except Exception as e:
        log.error(f"Vosk model load error: {e}")
        return

    # Whisper base — multilingual (Hebrew, Russian, Arabic, auto-detect)
    whisper_model = None
    try:
        whisper_model = WhisperModel("base", device="cpu", compute_type="int8")
        log.info("Whisper base model loaded")
    except Exception as e:
        log.warning(f"Whisper load error — non-English detection disabled: {e}")

    audio_fd       = 3
    alerted        = False
    resample_state = None
    whisper_buf    = bytearray()

    # Sliding window: deque of (monotonic_timestamp, text) tuples
    transcript_win  = deque()
    # Full transcript kept separately for FCM display (no expiry)
    transcript_disp = ""

    def add_text(text: str, label: str):
        nonlocal transcript_disp
        transcript_win.append((time.monotonic(), text))
        transcript_disp += " " + text
        log.info(f"STT-{label}: {text}")

    def get_window_text() -> str:
        now = time.monotonic()
        while transcript_win and now - transcript_win[0][0] > WINDOW_SECONDS:
            transcript_win.popleft()
        return " ".join(t for _, t in transcript_win)

    def check_and_alert(label: str):
        nonlocal alerted
        if alerted:
            return
        is_scam, matched_kws, score = detect_scam(get_window_text())
        if is_scam:
            conf = score_to_confidence(score)
            scam_type = classify_scam_type(matched_kws)
            log.warning(
                f"SCAM DETECTED ({label}): type={scam_type} "
                f"score={score} confidence={conf} keywords={matched_kws}"
            )
            send_fcm_alert(matched_kws, transcript_disp.strip(),
                           scam_type, caller_number, conf)
            alerted = True

    log.info("Audio analysis started")
    try:
        while True:
            data = os.read(audio_fd, 3200)
            if not data:
                log.info("Audio stream ended")
                break

            # EAGI delivers 8 kHz SLIN; upsample to 16 kHz for Vosk / Whisper
            resampled, resample_state = audioop.ratecv(
                data, 2, 1, 8000, 16000, resample_state
            )

            # English: Vosk streaming (fires on sentence boundary)
            if rec_en.AcceptWaveform(resampled):
                text = json.loads(rec_en.Result()).get("text", "").strip()
                if text:
                    add_text(text, "EN")
                    check_and_alert("vosk-en")

            # Multilingual: Whisper every 2 seconds
            whisper_buf.extend(resampled)
            if len(whisper_buf) >= WHISPER_CHUNK_BYTES:
                if whisper_model:
                    try:
                        audio_np = (
                            np.frombuffer(bytes(whisper_buf), dtype=np.int16)
                              .astype(np.float32) / 32768.0
                        )
                        segments, info = whisper_model.transcribe(
                            audio_np,
                            language=None,      # auto-detect: HE, RU, AR, mixed
                            beam_size=1,
                            best_of=1,
                            vad_filter=True,
                        )
                        text = " ".join(seg.text for seg in segments).strip()
                        if text:
                            add_text(text, f"ML({info.language})")
                            check_and_alert("whisper-ml")
                    except Exception as e:
                        log.warning(f"Whisper error: {e}")
                whisper_buf.clear()

    except OSError as e:
        log.info(f"Audio fd closed: {e}")
    except Exception as e:
        log.error(f"Unexpected error: {e}")

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
            log.warning(f"Whisper final error: {e}")

    check_and_alert("final")
    log.info(f"Call ended. transcript={transcript_disp.strip()!r}")


if __name__ == "__main__":
    main()