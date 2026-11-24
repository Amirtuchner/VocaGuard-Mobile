#!/opt/vocaguard-venv/bin/python3
"""HTTPS server on port 443.

Endpoints:
  POST /register          — register a new user (phone_number + fcm_token)
                            returns {sip_extension, sip_password, did_number}
  POST /register-token    — update FCM token for an existing user
                            body: JSON {phone_number, fcm_token}
  POST /verify-number/start — start CLI-capture number detection
                            body: JSON {fcm_token} → {verify_number, code, ttl}
  POST /accept-call       — AMI bridge: {channel, caller, phone_number}
  POST /hangup            — AMI hangup: {channel, phone_number}
"""
import os, ssl, json, re, socket, logging, sqlite3, secrets, subprocess, threading, time
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

# ---------------------------------------------------------------------------
# Paths / constants
# ---------------------------------------------------------------------------
SECRET_FILE      = "/opt/vocaguard/token_server_secret.txt"
AMI_SECRET_FILE  = "/opt/vocaguard/ami_secret.txt"
CERT_FILE        = "/opt/vocaguard/server.crt"
KEY_FILE         = "/opt/vocaguard/server.key"
# Domain cert (Let's Encrypt) served via SNI to clients that connect by
# hostname; clients connecting by bare IP still get the self-signed cert
# above, which old app builds pin. See __main__ TLS setup.
API_DOMAIN       = "api.vocaguard.com"
LE_CERT_FILE     = "/etc/letsencrypt/live/api.vocaguard.com/fullchain.pem"
LE_KEY_FILE      = "/etc/letsencrypt/live/api.vocaguard.com/privkey.pem"
TOKEN_FILE       = "/opt/vocaguard/fcm_token.txt"  # single-user fallback
DB_PATH          = "/opt/vocaguard/data/users.db"   # root:asterisk 0660 in a 2770 dir so AGI helpers (asterisk user) can write
PJSIP_USERS_FILE = "/etc/asterisk/pjsip_users.conf"
DID_NUMBER       = "+97233741493"
# Shared DIDWW number for US/Canada (+1) users: forwarding to the Israeli DID is
# an international forward, which most US carriers block or bill (2026-11-24).
US_DID_NUMBER    = "+13373828491"
_DID_CC = "972"

def _normalize_phone(number: str) -> str:
    # Digits only: also drops "(", ")", "." and invisible bidi marks that Hebrew
    # keyboards/RTL text fields can insert.
    n = re.sub(r"[^0-9]", "", number or "")
    if n.startswith("00"):
        n = n[2:]
    if n.startswith("0") and not n.startswith(_DID_CC):
        n = _DID_CC + n[1:]
    # "+972 052…" — users follow the "include your country code" hint but keep the
    # trunk 0, which produced rows like 9720523624141 that match no forwarded call.
    if n.startswith(_DID_CC + "0"):
        n = _DID_CC + n[len(_DID_CC) + 1:]
    # US/Canada number typed without the country code, e.g. "(555) 123-4567". The
    # carrier's Diversion header carries the leading 1, so without it incoming
    # calls never match the user. NANP area codes and exchanges start with 2-9.
    if _NANP10_RE.match(n):
        n = "1" + n
    return n


_NANP10_RE = re.compile(r"^[2-9]\d{2}[2-9]\d{6}$")
_NANP_RE   = re.compile(r"^1[2-9]\d{2}[2-9]\d{6}$")


def _shared_did_for(phone: str) -> str:
    """Shared forwarding DID for a normalized phone: the US number for +1 users,
    the Israeli one for everyone else."""
    return US_DID_NUMBER if _NANP_RE.match(phone) else DID_NUMBER


_IL_MOBILE_RE = re.compile(r"^9725\d{8}$")

def _is_il_mobile(phone: str) -> bool:
    """True for a well-formed Israeli mobile (972 5X XXX XXXX). Dedicated DIDs are
    Israeli and only Israeli carriers forward to them, so anything else — mistyped
    numbers, foreign numbers, and the junk Google Play's pre-launch robots type into
    the phone field after every upload — stays on the shared DID instead of using
    up the pool (and skips the paid Twilio carrier lookup)."""
    return bool(_IL_MOBILE_RE.match(phone))

AMI_HOST = "127.0.0.1"
AMI_PORT = 5038
AMI_USER = "vocaguard-bridge"

LOG_FILE = "/var/log/vocaguard_token.log"
logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("token_server")

# \Z, not $: "$" also matches before a trailing "\n", which would let a newline
# through into the AMI text protocol.
_CHANNEL_RE = re.compile(r"^PJSIP/[A-Za-z0-9_\-]{1,80}\Z")


def _safe_callerid(value) -> str:
    """Caller/phone values that end up in AMI action text, in the dialplan, and in
    a System() shell command. Keep only [0-9A-Za-z+] (numbers, "+", and words like
    "anonymous"), so CRLF/AMI-header injection and shell metacharacters can't get
    through (security audit 2026-11-23, finding 2)."""
    return re.sub(r"[^0-9A-Za-z+]", "", str(value or ""))[:32]


# Caregiver SMS/call alerts go out from VocaGuard's own Twilio number and cost
# money per message, so they're capped per destination and globally (audit
# finding 4). Real use is a handful per month.
_ALERT_PER_DEST_LIMIT = 3       # per destination number per hour
_ALERT_GLOBAL_LIMIT   = 40      # across all numbers per day
_alert_log: list[tuple[float, str]] = []   # (time.time(), "sms:<dest>" / "call:<dest>")
_alert_lock = threading.Lock()


def _alert_allowed(kind: str, dest: str) -> bool:
    now = time.time()
    key = f"{kind}:{dest}"
    with _alert_lock:
        _alert_log[:] = [(t, k) for t, k in _alert_log if now - t < 86400]
        if len(_alert_log) >= _ALERT_GLOBAL_LIMIT:
            return False
        if sum(1 for t, k in _alert_log if k == key and now - t < 3600) >= _ALERT_PER_DEST_LIMIT:
            return False
        _alert_log.append((now, key))
        return True


def _caregiver_dest(raw: str) -> str | None:
    """E.164 destination for a caregiver alert, or None if it isn't a plausible
    phone number (8–15 digits, not a short code / premium-looking garbage)."""
    n = _normalize_phone(raw)
    if not re.fullmatch(r"[1-9]\d{7,14}", n):
        return None
    return "+" + n


_URL_RE = re.compile(r"(https?:|www\.|://|\b[a-z0-9-]+\.(com|net|org|io|ly|me|co|info|xyz|link|app)\b)", re.I)

with open(SECRET_FILE) as f:
    EXPECTED_SECRET = f.read().strip()

with open(AMI_SECRET_FILE) as f:
    AMI_SECRET = f.read().strip()

with open("/opt/vocaguard/twilio_sid.txt") as f:
    TWILIO_SID = f.read().strip()
with open("/opt/vocaguard/twilio_token.txt") as f:
    TWILIO_TOKEN = f.read().strip()
with open("/opt/vocaguard/twilio_number.txt") as f:
    TWILIO_NUMBER = f.read().strip()

# ---------------------------------------------------------------------------
# SQLite user database
# ---------------------------------------------------------------------------

def db_connect():
    """Open a SQLite connection tuned for the threaded server.

    The HTTP server is a ThreadingMixIn, so each request runs on its own thread
    with its own connection. Plain sqlite3.connect() has a 0-length busy timeout,
    so any concurrent write returned "database is locked" (HTTP 500) immediately —
    which broke /register. A busy_timeout makes writers wait for the lock instead
    of failing. NOTE: do NOT enable WAL here — the AGI helpers (notify_incoming.py,
    scam_detector_bg.py) run as the 'asterisk' user with read-only access to the
    root-owned users.db, and WAL requires write access to the -shm/-wal files even
    for readers, which breaks their token lookups. Rollback-journal mode lets those
    read-only SELECTs work; busy_timeout alone prevents the lock errors.
    """
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def init_db():
    conn = db_connect()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            phone_number   TEXT    UNIQUE NOT NULL,
            fcm_token      TEXT,
            sip_extension  TEXT    UNIQUE NOT NULL,
            sip_password   TEXT    NOT NULL,
            created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS community_blocklist (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            phone_number   TEXT    NOT NULL,
            scam_type      TEXT    NOT NULL DEFAULT 'UNKNOWN',
            reported_by    TEXT    DEFAULT '',
            report_count   INTEGER DEFAULT 1,
            created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(phone_number)
        )
    """)
    # Pending number-verification sessions: the onboarding "detect my number" flow.
    # The app POSTs its fcm_token, we return a short numeric nonce; the app places a
    # call to our DID and sends the nonce as DTMF. verify_number.py (run by Asterisk)
    # reads this table to map nonce -> fcm_token, grabs the caller's ANI (the user's
    # real number) and pushes it back. created_at is time.time() (REAL) for TTL purge.
    conn.execute("""
        CREATE TABLE IF NOT EXISTS verify_sessions (
            code         TEXT    PRIMARY KEY,
            fcm_token    TEXT    NOT NULL,
            created_at   REAL    NOT NULL
        )
    """)
    # Per-user dedicated DID (robust multi-tenant routing). A user with a dedicated
    # DID is identified by the CALLED number (always present in the INVITE To:),
    # instead of the SIP Diversion header — which some carriers strip entirely,
    # breaking tenant routing (observed 2026-11-10: a forwarded call arrived with no
    # Diversion/History-Info at all). did_number NULL = user is on the shared DID and
    # routing falls back to Diversion (unchanged legacy behavior). did_pool holds spare
    # dedicated DIDs (bought in DIDWW, pointed at the trunk) waiting to be assigned.
    _cols = [r[1] for r in conn.execute("PRAGMA table_info(users)").fetchall()]
    if "did_number" not in _cols:
        conn.execute("ALTER TABLE users ADD COLUMN did_number TEXT")
    # Set (epoch) by notify_incoming.py when this user's FCM push bounces
    # UNREGISTERED (app uninstalled) — gates the one-time "cancel your call
    # forwarding" SMS. Cleared by /register and /register-token (reinstall or
    # in-place token rotation).
    if "uninstalled_at" not in _cols:
        conn.execute("ALTER TABLE users ADD COLUMN uninstalled_at REAL")
    # DID the user was moved OFF. Their handset may still forward there until it
    # re-dials *21* to the new DID, so routing treats them as a claimant of BOTH
    # (ambiguous → Diversion fallback) instead of handing those calls to whoever
    # else owns the old DID. Cleared when a call arrives on the new DID.
    if "prev_did" not in _cols:
        conn.execute("ALTER TABLE users ADD COLUMN prev_did TEXT")
    # Set by did_maintenance.py's daily FCM dry-run when the token is dead. Separate
    # from uninstalled_at so it doesn't suppress the call-time uninstall SMS.
    if "token_dead_at" not in _cols:
        conn.execute("ALTER TABLE users ADD COLUMN token_dead_at REAL")
    # Cached Twilio carrier lookup: 1 = sends Diversion (shared DID ok), 0 = not.
    # The app re-registers daily, so uncached lookups would cost per user per day.
    if "carrier_safe" not in _cols:
        conn.execute("ALTER TABLE users ADD COLUMN carrier_safe INTEGER")
    # DID (digits) the APP last received from /register. A user moved server-side
    # (e.g. by SMS) still runs an app that stores — and may re-dial *21* to — the old
    # DID, so prev_did is only cleared once did_acked == did_number.
    if "did_acked" not in _cols:
        conn.execute("ALTER TABLE users ADD COLUMN did_acked TEXT")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS did_pool (
            did_number  TEXT PRIMARY KEY,
            assigned_to TEXT
        )
    """)
    _pcols = [r[1] for r in conn.execute("PRAGMA table_info(did_pool)").fetchall()]
    # Last time a forwarded call arrived on this DID (set by notify_incoming.py).
    # A free DID is only re-assigned after DID_QUARANTINE_DAYS of silence, so a
    # former owner's stale forwarding can't ring a new owner's phone.
    if "last_call_at" not in _pcols:
        conn.execute("ALTER TABLE did_pool ADD COLUMN last_call_at REAL")
    if "released_at" not in _pcols:
        conn.execute("ALTER TABLE did_pool ADD COLUMN released_at REAL")
    conn.commit()
    conn.close()
    log.info("DB initialised at %s", DB_PATH)


def get_user_by_phone(phone: str) -> dict | None:
    phone = _normalize_phone(phone)
    conn = db_connect()
    row = conn.execute(
        "SELECT phone_number, fcm_token, sip_extension, sip_password "
        "FROM users WHERE phone_number=?", (phone,)
    ).fetchone()
    conn.close()
    if not row:
        return None
    return {
        "phone_number":  row[0],
        "fcm_token":     row[1],
        "sip_extension": row[2],
        "sip_password":  row[3],
    }


TRIAL_DAYS = 30  # app-managed free trial length (no card required until it ends)


def _trial_info(created_at) -> dict:
    """Trial fields derived from users.created_at (UTC 'YYYY-MM-DD HH:MM:SS').
    created_at is set ONCE at first registration and never reset, so reinstalling
    the app cannot grant a fresh trial. None => brand-new user (full trial now)."""
    from datetime import datetime, timezone
    now = datetime.now(timezone.utc)
    dt = now
    if created_at:
        try:
            dt = datetime.strptime(created_at, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        except Exception:
            dt = now
    elapsed_days = (now - dt).total_seconds() / 86400.0
    return {
        "trial_start_epoch": int(dt.timestamp()),
        "trial_days_left":   max(0, TRIAL_DAYS - int(elapsed_days)),
        "trial_active":      elapsed_days < TRIAL_DAYS,
    }


def _assign_dedicated_did(conn, phone: str) -> str | None:
    """Claim a free dedicated DID from the pool for this phone and record it on the
    user row. Returns the DID (digits, no '+') or None if the pool is empty."""
    quiet_since = time.time() - DID_QUARANTINE_DAYS * 86400
    # Never-called DIDs first, then the longest-silent released ones.
    row = conn.execute(
        "SELECT did_number FROM did_pool WHERE assigned_to IS NULL "
        "AND (last_call_at IS NULL OR last_call_at < ?) "
        "ORDER BY last_call_at IS NOT NULL, last_call_at, did_number LIMIT 1",
        (quiet_since,)
    ).fetchone()
    if not row:
        log.warning("DID pool exhausted — %s stays on the shared DID", phone)
        return None
    did = row[0]
    old = conn.execute("SELECT did_number FROM users WHERE phone_number=?", (phone,)).fetchone()
    old_did = old[0] if old else None
    # NB: (phone, did) — the params were once swapped, so the pool row never got
    # marked and every user was handed the same "dedicated" DID (2026-11-21).
    conn.execute("UPDATE did_pool SET assigned_to=?, released_at=NULL WHERE did_number=?", (phone, did))
    conn.execute("UPDATE users SET did_number=?, prev_did=? WHERE phone_number=?",
                 (did, old_did if old_did and old_did != did else None, phone))
    log.info("Assigned dedicated DID %s to %s (prev=%s)", did, phone, old_did)
    return did


def _diversion_safe(conn, phone: str) -> bool:
    """_carrier_sends_diversion with the result cached on the user row, so the daily
    app re-register doesn't pay for a Twilio lookup every time. Lookup failures are
    not cached (treated unsafe this time, retried next time)."""
    row = conn.execute("SELECT carrier_safe FROM users WHERE phone_number=?", (phone,)).fetchone()
    if row and row[0] is not None:
        return bool(row[0])
    safe, ok = _carrier_lookup(phone)
    if ok:
        conn.execute("UPDATE users SET carrier_safe=? WHERE phone_number=?", (int(safe), phone))
    return safe


def _ack_did(conn, phone: str, did: str):
    """Record the DID being returned to the app (see users.did_acked)."""
    conn.execute("UPDATE users SET did_acked=? WHERE phone_number=?",
                 (re.sub(r"[^0-9]", "", did or ""), phone))


def _did_owned_by_other(conn, did: str, phone: str) -> bool:
    """True if `did` is on this user's row but the pool says it belongs to someone
    else (or nobody). Left over from the swapped-params bug, which gave many users
    the same DID; such users must move to a DID of their own."""
    row = conn.execute("SELECT assigned_to FROM did_pool WHERE did_number=?", (did,)).fetchone()
    return row is not None and row[0] != phone


# Carriers CONFIRMED (from real forwarded-call traffic, 2026-11-10) to include the SIP
# Diversion header, so shared-DID routing is safe for them. Matched as lowercase
# substrings of Twilio Lookup's carrier_name. Everything NOT here (Partner/Orange,
# Cellcom, Pelephone, unknown, or a failed lookup) is treated as unsafe → dedicated DID.
# Add a carrier here only after verifying it sends Diversion.
DIVERSION_SAFE_CARRIERS = ("hot", "019", "telzar")

# A released DID must see no forwarded calls for this long before it goes to a new
# owner — otherwise a former owner's still-active *21* would ring the new owner.
DID_QUARANTINE_DAYS = 30


def _carrier_lookup(phone: str) -> tuple[bool, bool]:
    """(safe, lookup_ok). safe is True iff the number's carrier is a CONFIRMED
    Diversion-header sender (shared-DID routing is safe). False for known/suspected
    strippers, unknown carriers, or on ANY lookup failure — so the safe default is to
    give the user a dedicated DID."""
    try:
        from twilio.rest import Client
        e164 = phone if phone.startswith("+") else "+" + phone
        r = Client(TWILIO_SID, TWILIO_TOKEN).lookups.v2.phone_numbers(e164).fetch(
            fields="line_type_intelligence")
        name = ((r.line_type_intelligence or {}).get("carrier_name") or "").lower()
        safe = any(k in name for k in DIVERSION_SAFE_CARRIERS)
        log.info("Carrier lookup %s -> %r (diversion_safe=%s)", phone, name, safe)
        return safe, True
    except Exception as e:
        log.warning("Carrier lookup failed for %s (%s) -> treating as NOT diversion-safe", phone, e)
        return False, False


def register_user(phone: str, fcm_token: str) -> dict:
    """Create user or update their FCM token. Returns SIP credentials + trial status.

    DID assignment: a user keeps any dedicated DID already on their row; otherwise we
    try to claim one from did_pool (migrating existing users as DIDs become available);
    if the pool is empty they stay on the shared DID_NUMBER (did_number NULL) and
    routing falls back to the Diversion header."""
    # Store the canonical (normalized) phone so /delete, /register-token and the
    # incoming-call lookups all match. _normalize_phone strips the leading "+"
    # (PJSIP dials digits only); it is idempotent, so re-registration matches the
    # stored row and reuses the same extension instead of allocating a new one.
    phone = _normalize_phone(phone)
    conn = db_connect()
    existing = conn.execute(
        "SELECT sip_extension, sip_password, created_at, did_number FROM users WHERE phone_number=?",
        (phone,)
    ).fetchone()

    if existing:
        conn.execute(
            "UPDATE users SET fcm_token=?, uninstalled_at=NULL, token_dead_at=NULL WHERE phone_number=?",
            (fcm_token, phone)
        )
        # Keep an existing dedicated DID. Otherwise, only assign one if the carrier is
        # NOT a confirmed Diversion sender (Diversion-safe carriers stay on shared DID).
        did = existing[3]
        if did and _is_il_mobile(phone) and _did_owned_by_other(conn, did, phone):
            # Shares a DID with other users (swapped-params bug). Move to a free one;
            # if the pool is empty, keep the shared DID for now — clearing it would
            # not change where the handset forwards, only break routing further.
            new_did = _assign_dedicated_did(conn, phone)
            if new_did:
                log.info("Moved %s off shared DID %s -> %s", phone, did, new_did)
                did = new_did
        if not did and _is_il_mobile(phone) and not _diversion_safe(conn, phone):
            did = _assign_dedicated_did(conn, phone)
        _ack_did(conn, phone, did or _shared_did_for(phone))
        conn.commit()
        conn.close()
        log.info("Updated FCM token for existing user %s (did=%s)", phone, did or "shared")
        return {
            "sip_extension": existing[0],
            "sip_password":  existing[1],
            "did_number":    did or _shared_did_for(phone),
            **_trial_info(existing[2]),
        }

    # New user: assign next extension number. "vocaguard_" is 10 chars, so the
    # numeric suffix starts at position 11 (SQLite SUBSTR is 1-indexed). The old
    # code used position 12, which dropped the first digit of every extension and
    # produced tiny, colliding numbers — e.g. vocaguard_1001/1002 parsed as 1/2,
    # so the "next" extension was vocaguard_3, which already existed → UNIQUE
    # constraint failure on register. max(..., 1000)+1 keeps allocation monotonic
    # and always above both the true max and any stray small-numbered rows.
    row = conn.execute(
        "SELECT MAX(CAST(SUBSTR(sip_extension, 11) AS INTEGER)) FROM users "
        "WHERE sip_extension LIKE 'vocaguard_%'"
    ).fetchone()
    next_num  = max(row[0] or 0, 1000) + 1
    sip_ext   = f"vocaguard_{next_num}"
    sip_pass  = secrets.token_urlsafe(16)

    conn.execute(
        "INSERT INTO users (phone_number, fcm_token, sip_extension, sip_password) "
        "VALUES (?, ?, ?, ?)",
        (phone, fcm_token, sip_ext, sip_pass)
    )
    # Carrier-aware DID: Diversion-safe carriers (HOT/019) use the shared DID; everyone
    # else (Partner/Cellcom/Pelephone/unknown) gets a dedicated DID when the pool has one.
    # Non-Israeli-mobile numbers (typos, foreign, Play pre-launch robots) stay shared.
    if not _is_il_mobile(phone) or _diversion_safe(conn, phone):
        did = None
    else:
        did = _assign_dedicated_did(conn, phone)
    _ack_did(conn, phone, did or _shared_did_for(phone))
    conn.commit()
    conn.close()

    provision_pjsip_user(sip_ext, sip_pass)
    log.info("Registered new user %s → extension %s (did=%s)", phone, sip_ext, did or "shared")

    return {
        "sip_extension": sip_ext,
        "sip_password":  sip_pass,
        "did_number":    did or _shared_did_for(phone),
        **_trial_info(None),   # brand-new user → full trial starting now
    }


VERIFY_TTL = 300  # seconds a number-verification nonce stays valid


def create_verify_session(fcm_token: str) -> dict:
    """Create a one-shot number-verification nonce bound to an FCM token.

    Returns {verify_number, code, ttl}. The app dials verify_number and sends
    `code` as DTMF; verify_number.py maps it back to this fcm_token and pushes
    the caller's ANI (the device's real number) to the app. Purges expired
    nonces on every call so the table can't grow unbounded."""
    conn = db_connect()
    try:
        now = time.time()
        conn.execute("DELETE FROM verify_sessions WHERE created_at < ?", (now - VERIFY_TTL,))
        code = ""
        for _ in range(10):
            candidate = f"{secrets.randbelow(900000) + 100000}"  # 6 digits, no leading zero
            if not conn.execute("SELECT 1 FROM verify_sessions WHERE code=?", (candidate,)).fetchone():
                code = candidate
                break
        if not code:
            raise RuntimeError("could not allocate a free verification code")
        conn.execute(
            "INSERT OR REPLACE INTO verify_sessions (code, fcm_token, created_at) VALUES (?, ?, ?)",
            (code, fcm_token, now)
        )
        conn.commit()
    finally:
        conn.close()
    log.info("Verify session created code=%s tail=%s", code, fcm_token[-10:])
    return {"verify_number": DID_NUMBER, "code": code, "ttl": VERIFY_TTL}


def update_fcm_token(phone: str, fcm_token: str) -> bool:
    """Update FCM token for an existing user. Returns False if user not found."""
    phone = _normalize_phone(phone)
    conn = db_connect()
    cur = conn.execute(
        "UPDATE users SET fcm_token=?, uninstalled_at=NULL, token_dead_at=NULL WHERE phone_number=?",
        (fcm_token, phone)
    )
    conn.commit()
    updated = cur.rowcount > 0
    conn.close()
    if updated:
        log.info("FCM token refreshed for %s", phone)
    return updated


def delete_user(phone: str) -> bool:
    """Remove a user from the DB and from pjsip_users.conf, then reload PJSIP."""
    phone = _normalize_phone(phone)
    conn = db_connect()
    try:
        row = conn.execute(
            "SELECT sip_extension FROM users WHERE phone_number=?", (phone,)
        ).fetchone()
        if not row:
            return False
        ext = row[0]
        conn.execute("DELETE FROM users WHERE phone_number=?", (phone,))
        # Return their dedicated DID to the pool. It is only re-assigned after
        # DID_QUARANTINE_DAYS without calls (stale forwarding protection).
        conn.execute("UPDATE did_pool SET assigned_to=NULL, released_at=? WHERE assigned_to=?",
                     (time.time(), phone))
        conn.commit()
    finally:
        conn.close()

    try:
        with open(PJSIP_USERS_FILE, "r") as fh:
            lines = fh.readlines()
        skip_headers = {"[" + ext + "]", "[auth_" + ext + "]"}
        out, skip = [], False
        for line in lines:
            stripped = line.strip()
            if stripped.startswith("[") and stripped.endswith("]"):
                skip = stripped in skip_headers
            if not skip:
                out.append(line)
        with open(PJSIP_USERS_FILE, "w") as fh:
            fh.writelines(out)
    except Exception as e:
        log.error("delete_user: pjsip cleanup failed for %s: %s", ext, e)

    result = subprocess.run(
        ["asterisk", "-rx", "module reload res_pjsip.so"],
        capture_output=True, text=True, timeout=10
    )
    log.info("PJSIP reload after delete %s: %s", ext, (result.stdout + result.stderr).strip())
    log.info("User deleted: phone=%s ext=%s", phone, ext)
    return True


# ---------------------------------------------------------------------------
# Dynamic PJSIP provisioning
# ---------------------------------------------------------------------------

def provision_pjsip_user(ext: str, password: str):
    """Append a PJSIP endpoint/auth/aor block and reload res_pjsip."""
    # AOR name MUST match the extension username (To-header in REGISTER).
    # Asterisk PJSIP maps incoming REGISTER to AOR by matching the To username.
    # qualify_frequency=60: without this, a dead/stale contact (app killed, network
    # change) is only discovered when a real call tries to use it and the AMI
    # Originate silently fails — the phone just stays stuck on the "Connecting"
    # screen. OPTIONS pings every 60s let Asterisk mark the contact unavailable
    # ahead of time instead of during a live call.
    block = (
        f"\n[{ext}]\n"
        f"type=endpoint\n"
        f"context=vocaguard-user\n"
        f"auth=auth_{ext}\n"
        f"aors={ext}\n"
        f"allow=!all,opus,ulaw,alaw\n"
        f"direct_media=no\n"
        f"force_rport=yes\n"
        f"rewrite_contact=yes\n"
        # rtp_symmetric: send RTP back to the address/port the phone's RTP actually
        # comes FROM, not the (often wrong) address in its SDP. On cellular CGNAT the
        # carrier rebinds the phone's RTP port mid-call (Doze, radio sleep, handover);
        # without this, Asterisk keeps sending audio to the stale port and the call
        # goes silent until the path re-learns — the "audio drops then comes back"
        # mid-call symptom reported 2026-10-25. Required for reliable mobile audio.
        f"rtp_symmetric=yes\n"
        # rtp_keepalive: keepalive RTP during silence keeps the media pinhole open
        # so audio doesn't drop out after a quiet moment mid-call.
        f"rtp_keepalive=5\n"
        f"\n[auth_{ext}]\n"
        f"type=auth\n"
        f"auth_type=userpass\n"
        f"username={ext}\n"
        f"password={password}\n"
        f"\n[{ext}]\n"
        f"type=aor\n"
        f"max_contacts=1\n"
        f"remove_existing=yes\n"
        f"qualify_frequency=60\n"
        # qualify_timeout=10 (was 3): a phone dozing on LTE routinely can't answer the
        # OPTIONS ping within 3s, so Asterisk marked the contact Unreachable and dropped
        # it mid-session. 10s tolerates normal mobile radio latency without flapping.
        f"qualify_timeout=10\n"
    )
    with open(PJSIP_USERS_FILE, "a") as f:
        f.write(block)

    result = subprocess.run(
        ["asterisk", "-rx", "module reload res_pjsip.so"],
        capture_output=True, text=True, timeout=10
    )
    log.info("PJSIP reload for %s: %s", ext, (result.stdout + result.stderr).strip())

# ---------------------------------------------------------------------------
# AMI helpers
# ---------------------------------------------------------------------------

def _ami_recv(s: socket.socket) -> str:
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf.decode(errors="replace")


def ami_originate(orig_channel: str, orig_caller: str, sip_extension: str, user_phone: str):
    """Originate a call to the user's SIP extension and bridge with waiting channel."""
    # Sanitize at the sink too: these values come from the app request body.
    if not _CHANNEL_RE.match(orig_channel):
        raise ValueError("invalid channel")
    orig_caller   = _safe_callerid(orig_caller) or "anonymous"
    user_phone    = _safe_callerid(user_phone)
    sip_extension = re.sub(r"[^A-Za-z0-9_]", "", sip_extension)
    s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=15)
    try:
        s.recv(1024)
        s.sendall(
            f"Action: Login\r\nUsername: {AMI_USER}\r\nSecret: {AMI_SECRET}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        # Mark incoming channel as accepted so dialplan skips EAGI
        s.sendall(
            f"Action: Setvar\r\nChannel: {orig_channel}\r\n"
            f"Variable: VG_ACCEPTED\r\nValue: 1\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        s.sendall(
            f"Action: Originate\r\n"
            f"Channel: PJSIP/{sip_extension}\r\n"
            f"Context: vocaguard-user\r\n"
            f"Exten: s\r\nPriority: 1\r\n"
            f"Variable: ORIG_CHANNEL={orig_channel}\r\n"
            f"Variable: ORIG_CALLER={orig_caller}\r\n"
            f"Variable: USER_PHONE={user_phone}\r\n"
            f"Variable: PJSIP_HEADER(add,Call-Info)=<urn:ietf:params:answer-after=0>\r\n"
            f"CallerID: {orig_caller}\r\n"
            f"Timeout: 30000\r\nAsync: yes\r\n\r\n"
            .encode()
        )
        resp = _ami_recv(s)
        s.sendall(b"Action: Logoff\r\n\r\n")
    finally:
        s.close()

    if "Response: Error" in resp:
        raise RuntimeError(f"AMI error: {resp[:200]}")



# ---------------------------------------------------------------------------
# Caregiver call alert helpers
# ---------------------------------------------------------------------------

def generate_alert_audio(scam_type: str, confidence_pct: int, senior_name: str) -> str:
    import tempfile, subprocess, os
    msg = (
        f"VocaGuard alert. {senior_name}'s phone detected a {scam_type} "
        f"with {confidence_pct} percent confidence. "
        "Please check on them. This is an automated alert from VocaGuard."
    )
    raw_wav = tempfile.mktemp(suffix="_raw.wav", dir="/tmp")
    final_wav = tempfile.mktemp(suffix="_alert.wav", dir="/tmp")
    try:
        subprocess.run(
            ["/usr/bin/espeak-ng", "-w", raw_wav, msg],
            check=True, timeout=15, capture_output=True
        )
        subprocess.run(
            ["/usr/bin/sox", raw_wav, "-r", "8000", "-c", "1",
             "-e", "signed-integer", "-b", "16", final_wav],
            check=True, timeout=15, capture_output=True
        )
    finally:
        if os.path.exists(raw_wav):
            os.remove(raw_wav)
    return final_wav[:-4]


def ami_originate_caregiver(caregiver_number: str, alert_file_no_ext: str):
    s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=15)
    try:
        s.recv(1024)
        s.sendall(
            f"Action: Login\r\nUsername: {AMI_USER}\r\nSecret: {AMI_SECRET}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)
        s.sendall(
            f"Action: Originate\r\n"
            f"Channel: PJSIP/{_normalize_phone(caregiver_number)}@didww-trunk\r\n"
            f"Context: caregiver-alert\r\n"
            f"Exten: s\r\nPriority: 1\r\n"
            f"Variable: ALERT_FILE={alert_file_no_ext}\r\n"
            f"CallerID: VocaGuard Alert <{DID_NUMBER}>\r\n"
            f"Timeout: 45000\r\nAsync: yes\r\n\r\n"
            .encode()
        )
        resp = _ami_recv(s)
        s.sendall(b"Action: Logoff\r\n\r\n")
    finally:
        s.close()
    if "Response: Error" in resp:
        raise RuntimeError(f"AMI caregiver originate error: {resp[:200]}")

def ami_hangup(channel: str, sip_extension: str):
    """Hang up the incoming channel and the user's SIP extension channels."""
    s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=15)
    try:
        s.recv(1024)
        s.sendall(
            f"Action: Login\r\nUsername: {AMI_USER}\r\nSecret: {AMI_SECRET}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        s.sendall(f"Action: Hangup\r\nChannel: {channel}\r\n\r\n".encode())
        _ami_recv(s)

        # Hang up user's SIP extension channels
        s.sendall(b"Action: CoreShowChannels\r\n\r\n")
        raw = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            raw += chunk
            if b"EventList: Complete" in raw or b"\r\n\r\n" in raw[-20:]:
                break

        prefix = f"PJSIP/{sip_extension}-"
        for line in raw.decode(errors="replace").splitlines():
            if line.startswith(f"Channel: {prefix}"):
                ch = line.split(": ", 1)[1].strip()
                s.sendall(f"Action: Hangup\r\nChannel: {ch}\r\n\r\n".encode())
                _ami_recv(s)

        s.sendall(b"Action: Logoff\r\n\r\n")
    finally:
        s.close()

def ami_channel_exists(channel: str) -> bool:
    """Check whether an Asterisk channel is still alive via AMI Status.

    Guards /accept-call against bridging into a call whose caller already hung
    up (e.g. user answers the still-ringing app screen minutes later). On any
    AMI failure, err on the side of True so a transient AMI hiccup can't block
    a legitimate accept.
    """
    try:
        s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=5)
        try:
            s.recv(1024)
            s.sendall(
                f"Action: Login\r\nUsername: {AMI_USER}\r\nSecret: {AMI_SECRET}\r\n\r\n"
                .encode()
            )
            _ami_recv(s)
            s.sendall(f"Action: Status\r\nChannel: {channel}\r\n\r\n".encode())
            resp = _ami_recv(s)
            s.sendall(b"Action: Logoff\r\n\r\n")
        finally:
            s.close()
        # Only an explicit "No such channel" means the caller is gone. Any other
        # error (e.g. "Permission denied" — this AMI user has had class quirks,
        # see notify_incoming.py's CoreShowChannels note) must NOT block accepts.
        if "Response: Error" in resp and "No such channel" in resp:
            return False
        return True
    except Exception as e:
        log.warning("ami_channel_exists(%s) check failed, assuming alive: %s", channel, e)
        return True


def _ami_originate_safe(channel, caller, sip_ext, phone):
    """Wrapper that logs errors from ami_originate without crashing the server."""
    try:
        ami_originate(channel, caller, sip_ext, phone)
        log.info("Bridge originated: %s → %s (user=%s)", channel, sip_ext, phone)
    except Exception as e:
        log.error("ami_originate error: %s", e)

# Channels already accepted (AMI Originate started), so a duplicate /accept-call
# for the same channel — e.g. a double-tap on Accept, or a client retry that
# raced a slow-but-successful first request — is a no-op instead of ringing the
# user's phone a second time. Cleared on /hangup.
#
# IMPORTANT: entries carry a timestamp and expire after _ACCEPTED_TTL. Asterisk
# channel names (PJSIP/didww-trunk-0000NNNN) are NOT globally unique — the counter
# resets to 0 whenever Asterisk restarts (nightly unattended-upgrades), while this
# process keeps running. Without a TTL, a channel ID reused the next day matched a
# stale entry from a prior call, so the user's Accept was wrongly rejected as a
# duplicate and the call never bridged (observed 2026-08-23, first morning calls
# after the ~06:55 Asterisk restart). A genuine double-tap/retry happens within
# seconds, so a short TTL still de-dupes real duplicates while letting a reused ID
# a day later bridge normally.
_ACCEPTED_TTL = 90.0  # seconds
_accepted_channels: dict[str, float] = {}   # channel -> time.monotonic() when accepted
_accepted_channels_lock = threading.Lock()


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):

    # Don't advertise the Python/BaseHTTP version in the Server header (audit finding 10).
    server_version = "VocaGuard"
    sys_version = ""

    def _auth_ok(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {EXPECTED_SECRET}"

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(length).decode())

    def _send(self, code: int, body: dict | None = None):
        self.send_response(code)
        if body is not None:
            payload = json.dumps(body).encode()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self.end_headers()

    def do_POST(self):
        if not self._auth_ok():
            self._send(403)
            return

        if self.path == "/register":
            try:
                data  = self._read_json()
                phone = data.get("phone_number", "").strip()
                fcm   = data.get("fcm_token", "").strip()
                if not phone or not fcm:
                    self._send(400, {"error": "phone_number and fcm_token required"})
                    return
                result = register_user(phone, fcm)
                self._send(200, result)
            except Exception as e:
                log.error("register error: %s", e)
                self._send(500, {"error": "internal error"})

        elif self.path == "/verify-number/start":
            # Onboarding "detect my number": bind a nonce to the caller's fcm_token.
            try:
                data = self._read_json()
                fcm  = data.get("fcm_token", "").strip()
                if not fcm:
                    self._send(400, {"error": "fcm_token required"})
                    return
                self._send(200, create_verify_session(fcm))
            except Exception as e:
                log.error("verify-number/start error: %s", e)
                self._send(500, {"error": "internal error"})

        elif self.path == "/register-token":
            # Accepts JSON {phone_number, fcm_token} or legacy raw token string
            try:
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length)
                try:
                    data  = json.loads(raw.decode())
                    phone = data.get("phone_number", "").strip()
                    fcm   = data.get("fcm_token", "").strip()
                    # The single-user fallback file (TOKEN_FILE) receives alerts for
                    # every call that matches no user, so requests may no longer
                    # overwrite it (audit finding 3). Only registered users' tokens
                    # get updated; none of these fallback writes happened in the
                    # 30 days before this change.
                    if phone and fcm:
                        if not update_fcm_token(phone, fcm):
                            log.warning("register-token: unknown phone %s, ignored", _safe_callerid(phone))
                    elif fcm:
                        log.warning("register-token: token without phone, ignored")
                except (json.JSONDecodeError, AttributeError):
                    log.warning("register-token: legacy raw-token request, ignored")
                self._send(200)
            except Exception as e:
                log.error("register-token error: %s", e)
                self._send(500)

        elif self.path == "/accept-call":
            try:
                data    = self._read_json()
                channel = data.get("channel", "")
                caller  = data.get("caller", "")
                phone   = data.get("phone_number", "").strip()

                if not _CHANNEL_RE.match(channel):
                    self._send(400, {"error": "invalid channel"})
                    return

                # Caller may have hung up while the app was still ringing (the
                # dialplan's 60s window expired, or they gave up). Answering a
                # dead channel used to bridge the user into a 1-second phantom
                # call (2026-08-02); tell the app the call is gone instead.
                if not ami_channel_exists(channel):
                    log.warning("accept-call: channel %s is gone (caller hung up)", channel)
                    self._send(410, {"error": "call_gone"})
                    return

                user    = get_user_by_phone(phone) if phone else None
                sip_ext = user["sip_extension"] if user else "vocaguard"  # single-user fallback

                now = time.monotonic()
                with _accepted_channels_lock:
                    # Drop expired entries so a channel ID reused after an Asterisk
                    # restart is not mistaken for a still-active accept.
                    for ch, ts in list(_accepted_channels.items()):
                        if now - ts >= _ACCEPTED_TTL:
                            del _accepted_channels[ch]
                    prev = _accepted_channels.get(channel)
                    already_accepted = prev is not None and (now - prev) < _ACCEPTED_TTL
                    if not already_accepted:
                        _accepted_channels[channel] = now

                # Respond 200 immediately so the Android client doesn't time out and
                # retry — a duplicate request would trigger a second AMI Originate and
                # a second SIP INVITE (causing a double-ring on the user's phone). The
                # _accepted_channels check above is the actual guard against that: it
                # catches duplicates that reach here as fully legitimate, successful
                # requests (e.g. a double-tap on Accept), not just client retries.
                self._send(200)
                if already_accepted:
                    log.warning("accept-call: duplicate request for %s, ignoring", channel)
                else:
                    threading.Thread(
                        target=_ami_originate_safe,
                        args=(channel, caller, sip_ext, phone),
                        daemon=True
                    ).start()
            except Exception as e:
                log.error("accept-call error: %s", e)
                self._send(500)

        elif self.path == "/hangup":
            try:
                data    = self._read_json()
                channel = data.get("channel", "")
                phone   = data.get("phone_number", "").strip()

                if not _CHANNEL_RE.match(channel):
                    self._send(400, {"error": "invalid channel"})
                    return

                user    = get_user_by_phone(phone) if phone else None
                sip_ext = user["sip_extension"] if user else "vocaguard"

                with _accepted_channels_lock:
                    _accepted_channels.pop(channel, None)

                ami_hangup(channel, sip_ext)
                log.info("Hangup: %s (user=%s ext=%s)", channel, phone, sip_ext)
                self._send(200)
            except Exception as e:
                log.error("hangup error: %s", e)
                self._send(500)


        elif self.path == "/call_caregiver":
            try:
                data       = self._read_json()
                caregiver  = data.get("caregiver_number", "").strip()
                scam_type  = data.get("scam_type", "Suspicious Call").strip()
                confidence = int(data.get("confidence_pct", 0))
                senior     = data.get("senior_name", "your family member").strip() or "your family member"
                to_number = _caregiver_dest(caregiver)
                if not to_number:
                    self._send(400, {"error": "valid caregiver_number required"})
                    return
                if not _alert_allowed("call", to_number):
                    log.warning("call_caregiver: rate limit hit for %s", to_number)
                    self._send(429, {"error": "rate limited"})
                    return
                # Always our own Twilio number: the caller ID used to come from the
                # request body (spoofable). Text fields are length-capped and
                # XML-escaped before going into TwiML, so they can't inject verbs
                # like <Dial> (audit finding 4).
                from xml.sax.saxutils import escape as _xml_escape
                caller_id  = TWILIO_NUMBER
                senior     = _xml_escape(senior[:40])
                scam_type  = _xml_escape(scam_type[:40])
                confidence = max(0, min(100, confidence))
                log.info("call_caregiver: to=%s", to_number)
                def _do_call():
                    try:
                        from twilio.rest import Client
                        msg = (
                            f"VocaGuard alert. {senior}s phone detected a {scam_type} "
                            f"with {confidence} percent confidence. "
                            "Please check on them. This is an automated alert from VocaGuard."
                        )
                        twiml = (
                            "<Response>"
                            "<Pause length=\"1\"/>"+
                            f"<Say voice=\"alice\">{msg}</Say>"+
                            "<Pause length=\"2\"/>"+
                            f"<Say voice=\"alice\">{msg}</Say>"+
                            "</Response>"
                        )
                        call = Client(TWILIO_SID, TWILIO_TOKEN).calls.create(
                            to=to_number,
                            from_=caller_id,
                            twiml=twiml
                        )
                        log.info("Twilio call SID %s to %s", call.sid, to_number)
                    except Exception as ex:
                        log.error("call_caregiver twilio error: %s", ex)
                threading.Thread(target=_do_call, daemon=True).start()
                self._send(200, {"status": "calling"})
            except Exception as e:
                log.error("call_caregiver error: %s", e)
                self._send(500, {"error": "internal error"})

        elif self.path == "/alert_caregiver":
            # Send a scam-alert SMS to the caregiver via Twilio. Replaces the
            # app's on-device SEND_SMS (removed to comply with Play's SMS policy —
            # SMS was an optional feature, not core functionality). The app POSTs
            # the caregiver number and the message text; we relay it from the
            # server's Twilio number.
            try:
                data      = self._read_json()
                caregiver = data.get("caregiver_number", "").strip()
                message   = data.get("message", "").strip()
                to_number = _caregiver_dest(caregiver)
                if not to_number or not message:
                    self._send(400, {"error": "valid caregiver_number and message required"})
                    return
                # Only VocaGuard alerts, and no links: this relay used to send any
                # text to any number from our Twilio number (audit finding 4).
                # The app always prefixes its alerts with "[VocaGuard]".
                if not message.startswith("[VocaGuard]") or _URL_RE.search(message):
                    log.warning("alert_caregiver: rejected message to %s", to_number)
                    self._send(400, {"error": "message not allowed"})
                    return
                if not _alert_allowed("sms", to_number):
                    log.warning("alert_caregiver: rate limit hit for %s", to_number)
                    self._send(429, {"error": "rate limited"})
                    return
                def _do_sms():
                    try:
                        from twilio.rest import Client
                        sms = Client(TWILIO_SID, TWILIO_TOKEN).messages.create(
                            to=to_number, from_=TWILIO_NUMBER, body=message[:320]
                        )
                        log.info("Twilio SMS SID %s to %s", sms.sid, to_number)
                    except Exception as ex:
                        log.error("alert_caregiver twilio error: %s", ex)
                threading.Thread(target=_do_sms, daemon=True).start()
                self._send(200, {"status": "sms_queued"})
            except Exception as e:
                log.error("alert_caregiver error: %s", e)
                self._send(500, {"error": "internal error"})

        elif self.path == "/report-scam":
            try:
                data   = self._read_json()
                number = data.get("number", "").strip()
                stype  = data.get("type", "UNKNOWN").strip()
                reporter = data.get("reporter", "").strip()
                if not number:
                    self._send(400, {"error": "number required"})
                    return
                conn = db_connect()
                conn.execute(
                    "INSERT INTO community_blocklist (phone_number, scam_type, reported_by) "
                    "VALUES (?, ?, ?) "
                    "ON CONFLICT(phone_number) DO UPDATE SET "
                    "report_count = report_count + 1, scam_type = excluded.scam_type",
                    (number, stype, reporter)
                )
                conn.commit()
                count = conn.execute("SELECT COUNT(*) FROM community_blocklist").fetchone()[0]
                conn.close()
                log.info("Scam reported: %s type=%s by=%s (total=%d)", number, stype, reporter, count)
                self._send(200, {"status": "reported", "total": count})
            except Exception as e:
                log.error("report-scam error: %s", e)
                self._send(500, {"error": "internal error"})

        elif self.path == "/remove-scam":
            try:
                data   = self._read_json()
                number = data.get("number", "").strip()
                reporter = data.get("reporter", "").strip()
                if not number:
                    self._send(400, {"error": "number required"})
                    return
                conn = db_connect()
                # A user can only withdraw their own report; this used to delete
                # any number for anyone (audit finding 5). Same response either
                # way, so the app's behavior is unchanged.
                cur = conn.execute(
                    "DELETE FROM community_blocklist WHERE phone_number = ? "
                    "AND reported_by = ? AND reported_by != ''",
                    (number, reporter)
                )
                conn.commit()
                removed = cur.rowcount
                conn.close()
                log.info("Scam removal: %s by=%s removed=%d", number, reporter, removed)
                self._send(200, {"status": "removed", "number": number})
            except Exception as e:
                log.error("remove-scam error: %s", e)
                self._send(500, {"error": "internal error"})

        elif self.path == "/blocklist":
            try:
                conn = db_connect()
                rows = conn.execute(
                    "SELECT phone_number, scam_type FROM community_blocklist"
                ).fetchall()
                conn.close()
                result = [{"number": r[0], "type": r[1]} for r in rows]
                self._send(200, result)
            except Exception as e:
                log.error("blocklist error: %s", e)
                self._send(500, {"error": "internal error"})

        else:
            self._send(404)

    def do_GET(self):
        if self.path == "/blocklist":
            try:
                conn = db_connect()
                rows = conn.execute(
                    "SELECT phone_number, scam_type FROM community_blocklist"
                ).fetchall()
                conn.close()
                result = [{"number": r[0], "type": r[1]} for r in rows]
                self._send(200, result)
            except Exception as e:
                log.error("blocklist error: %s", e)
                self._send(500, {"error": "internal error"})
        else:
            self._send(404)

    def do_DELETE(self):
        if not self._auth_ok():
            self._send(403)
            return
        if self.path == "/delete":
            try:
                data  = self._read_json()
                phone = data.get("phone_number", "").strip()
                if not phone:
                    self._send(400, {"error": "phone_number required"})
                    return
                phone = _normalize_phone(phone)
                found = delete_user(phone)
                if found:
                    log.info("Data deletion complete for %s", phone)
                    self._send(200, {"status": "deleted"})
                else:
                    self._send(404, {"error": "user not found"})
            except Exception as e:
                log.error("delete error: %s", e)
                self._send(500, {"error": "internal error"})
        else:
            self._send(404)

    def log_message(self, fmt, *args):
        log.info("%s - - %s", self.address_string(), fmt % args)


class ReuseHTTPServer(ThreadingMixIn, HTTPServer):
    allow_reuse_address = True
    daemon_threads = True          # so hung request threads don't block shutdown
    request_queue_size = 64        # allow more pending connections

    def get_request(self):
        # The listening socket is wrapped with do_handshake_on_connect=False,
        # so accept() returns immediately without doing the TLS handshake.
        # A stalled handshake previously froze the accept loop (main thread)
        # forever — one port scanner could hang the whole server.
        sock, addr = self.socket.accept()
        sock.settimeout(20)        # bounds handshake AND request reads
        return sock, addr

    def finish_request(self, request, client_address):
        # Runs in the per-connection worker thread (ThreadingMixIn):
        # do the deferred TLS handshake here, where a stall only costs
        # one daemon thread instead of the accept loop.
        try:
            request.do_handshake()
        except Exception as e:
            log.info("TLS handshake failed from %s: %s", client_address[0], e)
            return
        super().finish_request(request, client_address)


if __name__ == "__main__":
    import threading, time, urllib.request

    init_db()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT_FILE, KEY_FILE)

    # Serve the Let's Encrypt cert to clients that ask for API_DOMAIN via SNI
    # (app builds >= 1.0.5 use the hostname and validate against system CAs).
    # Clients with no matching SNI — old builds hitting the bare IP — fall
    # through to the pinned self-signed cert, so they keep working unchanged.
    # Guarded on file existence: before certbot has issued the cert, behavior
    # is identical to the old single-cert setup.
    if os.path.exists(LE_CERT_FILE) and os.path.exists(LE_KEY_FILE):
        le_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        le_context.load_cert_chain(LE_CERT_FILE, LE_KEY_FILE)

        def _sni_callback(sslobj, server_name, _ctx):
            if server_name == API_DOMAIN:
                sslobj.context = le_context
            return None

        context.sni_callback = _sni_callback
        log.info("SNI enabled: %s -> Let's Encrypt cert, bare IP -> self-signed", API_DOMAIN)

    server = ReuseHTTPServer(("0.0.0.0", 443), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True,
                                        do_handshake_on_connect=False)

    # --- Watchdog: self-check every 90s, restart if unresponsive ---
    def _watchdog():
        """Periodically probe the server; if it doesn't respond, force exit.
        systemd Restart=always will bring us back up.
        Uses subprocess curl with hard timeout to avoid the watchdog itself
        getting stuck on a hung TLS handshake."""
        failures = 0
        while True:
            time.sleep(90)
            try:
                result = subprocess.run(
                    ["curl", "-sk", "--max-time", "10", "-o", "/dev/null",
                     "-w", "%{http_code}", "https://127.0.0.1:443/"],
                    capture_output=True, text=True, timeout=15
                )
                if result.stdout.strip() in ("404", "200", "403"):
                    failures = 0
                else:
                    raise RuntimeError(f"curl exit={result.returncode}")
            except Exception as e:
                failures += 1
                log.warning("Watchdog: health check failed (%d): %s", failures, e)
                if failures >= 2:
                    log.error("Watchdog: server unresponsive, forcing restart")
                    os._exit(1)  # systemd will restart us

    wd = threading.Thread(target=_watchdog, daemon=True)
    wd.start()

    log.info("Token server started on port 443 (multi-tenant, watchdog enabled)")
    server.serve_forever()
