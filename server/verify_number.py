#!/opt/vocaguard-venv/bin/python3
"""AGI: number verification via CLI (caller-ID) capture.

Invoked from [vocaguard-incoming] when a user calls the DID *directly* (no
Diversion header) and sends a DTMF nonce — this is the onboarding "detect my
number" flow. We map the nonce back to the app instance's FCM token, read the
caller's ANI (the device's own mobile number, as seen by DIDWW/Asterisk), and
push it to the app so onboarding can auto-fill + register it with no typing.

Runs as the 'asterisk' OS user with READ-ONLY access to users.db — so it never
writes (no session delete here); token_server purges expired nonces by TTL.

argv: <code> <caller_ani>
"""
import sys, time, re, sqlite3
import firebase_admin
from firebase_admin import credentials, messaging

SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
DB_PATH         = "/opt/vocaguard/users.db"
LOG             = "/var/log/vocaguard_notify.log"
VERIFY_TTL      = 300
_DID_CC         = "972"


def _log(msg: str):
    try:
        with open(LOG, "a") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} VERIFY {msg}\n")
    except Exception:
        pass


def _drain_agi_env():
    """Consume the AGI environment Asterisk writes on stdin, so the pipe is
    read cleanly. We take our inputs from argv, not channel GETs, so we just
    read until the blank separator line and move on."""
    try:
        while True:
            line = sys.stdin.readline()
            if not line or line.strip() == "":
                break
    except Exception:
        pass


def _e164(num: str) -> str:
    """Normalize a raw ANI to +<cc>... E.164. DIDWW may deliver 0XXXXXXXXX,
    00972XXXXXXXXX, 972XXXXXXXXX, or +972XXXXXXXXX — all map to +972XXXXXXXXX."""
    n = re.sub(r"[^0-9]", "", num or "")
    if not n:
        return ""
    if n.startswith("00"):
        n = n[2:]
    if n.startswith("0"):
        n = _DID_CC + n[1:]
    return "+" + n


def main():
    _drain_agi_env()
    code = (sys.argv[1] if len(sys.argv) > 1 else "").strip()
    ani  = (sys.argv[2] if len(sys.argv) > 2 else "").strip()
    _log(f"code={code!r} ani={ani!r}")
    if not code or not ani:
        _log("missing code or ANI — abort")
        return

    try:
        conn = sqlite3.connect(DB_PATH, timeout=30)
        row = conn.execute(
            "SELECT fcm_token, created_at FROM verify_sessions WHERE code=?", (code,)
        ).fetchone()
        conn.close()
    except Exception as e:
        _log(f"DB error: {e}")
        return

    if not row:
        _log("no session for code — abort (expired or wrong nonce)")
        return
    fcm_token, created_at = row[0], row[1]
    if time.time() - (created_at or 0) > VERIFY_TTL:
        _log("session expired — abort")
        return

    phone = _e164(ani)
    if not phone or len(phone) < 8:
        _log(f"ANI did not normalize to a usable number: {ani!r} -> {phone!r}")
        return

    try:
        firebase_admin.initialize_app(credentials.Certificate(SERVICE_ACCOUNT))
        msg = messaging.Message(
            data={"type": "number_verified", "phone_number": phone},
            android=messaging.AndroidConfig(priority="high"),
            token=fcm_token,
        )
        r = messaging.send(msg)
        _log(f"pushed number_verified {phone} tail={fcm_token[-10:]} id={r}")
    except Exception as e:
        _log(f"FCM send error: {e}")


if __name__ == "__main__":
    main()
