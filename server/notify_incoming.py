#!/opt/vocaguard-venv/bin/python3
"""Lightweight pre-answer AGI: sends FCM incoming_call alert immediately.
   Includes the Asterisk channel name so the app can signal Accept via AMI.
   Also clears any stale PJSIP/vocaguard-* channels so the bridge can succeed.
   Reads ORIGINAL_CALLED (from REDIRECTING(from-num)) to identify which user
   the call was originally for, then looks up their FCM token from the DB.
"""
import sys, os, json, subprocess, re, sqlite3, time
import firebase_admin
from firebase_admin import credentials, messaging

SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"   # single-user fallback
DB_PATH         = "/opt/vocaguard/users.db"


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


def agi_get_variable(name: str) -> str:
    """Read a channel variable via the AGI GET VARIABLE command."""
    sys.stdout.write(f"GET VARIABLE {name}\n")
    sys.stdout.flush()
    response = sys.stdin.readline().strip()
    match = re.search(r'\((.+)\)', response)
    return match.group(1) if match else ""


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
        except Exception as e:
            sys.stdout.write(f'VERBOSE "DB lookup warning: {e}" 1\n')
            sys.stdout.flush()
    # Fallback: single-user token file
    try:
        return open(FCM_TOKEN_FILE).read().strip()
    except Exception:
        return ""


def cleanup_stale_vocaguard_channels():
    """Hang up any lingering PJSIP/vocaguard_*-* channels before bridging a new call.

    Uses the Asterisk CLI (not AMI) — CoreShowChannels was silently returning
    "Permission denied" for the vocaguard-bridge AMI user regardless of granted
    read classes, so this safety net was never actually running. The AGI process
    runs as the asterisk OS user, which owns asterisk.ctl, so `asterisk -rx` works.
    """
    try:
        result = subprocess.run(
            ["asterisk", "-rx", "core show channels concise"],
            capture_output=True, text=True, timeout=5,
        )

        stale = []
        for line in result.stdout.splitlines():
            fields = line.split("!")
            if len(fields) < 5:
                continue
            channel, state = fields[0], fields[4]
            if not channel.startswith("PJSIP/vocaguard"):
                continue
            # Never kill a channel that is Up (actively bridged)
            if state == "Up":
                sys.stdout.write(f'VERBOSE "Skipping active channel {channel}" 1\n')
                sys.stdout.flush()
                continue
            stale.append(channel)

        for ch in stale:
            subprocess.run(
                ["asterisk", "-rx", f"channel request hangup {ch}"],
                capture_output=True, timeout=5,
            )

        if stale:
            sys.stdout.write(f'VERBOSE "Cleared {len(stale)} stale channel(s)" 1\n')
            sys.stdout.flush()
    except Exception as e:
        sys.stdout.write(f'VERBOSE "cleanup_stale warning: {e}" 1\n')
        sys.stdout.flush()


DEDUP_TTL = 120  # seconds — ignore duplicate FCMs for same caller within this window
DEDUP_DIR  = "/tmp/vg_dedup"


def _dedup_key(caller: str) -> str:
    import hashlib
    return os.path.join(DEDUP_DIR, "caller_" + hashlib.md5(caller.encode()).hexdigest())


def _is_duplicate(caller: str) -> bool:
    """Return True if this caller already triggered an FCM within DEDUP_TTL seconds."""
    if not caller:
        return False
    try:
        os.makedirs(DEDUP_DIR, exist_ok=True)
        key = _dedup_key(caller)
        if os.path.exists(key):
            age = time.time() - os.path.getmtime(key)
            if age < DEDUP_TTL:
                return True
        # Touch the file to mark this caller as recently notified
        open(key, "w").close()
    except Exception:
        pass
    return False


def main():
    agi_vars = agi_init()
    caller  = agi_vars.get("agi_callerid", "")
    channel = agi_vars.get("agi_channel", "")

    # Read original called number (set in extensions.conf from REDIRECTING(from-num))
    diversion_number = agi_get_variable("ORIGINAL_CALLED")

    # Deduplicate: if same caller was notified recently, skip FCM (DIDWW/Twilio retry)
    if _is_duplicate(caller):
        sys.stdout.write(f'VERBOSE "Duplicate FCM suppressed for {caller}" 1\n')
        sys.stdout.flush()
        return

    cleanup_stale_vocaguard_channels()

    try:
        cred = credentials.Certificate(SERVICE_ACCOUNT)
        firebase_admin.initialize_app(cred)

        token = get_fcm_token_for_number(diversion_number)
        if token:
            data = {
                "type":             "incoming_call",
                "caller_number":    caller,
                "asterisk_channel": channel,
            }
            if diversion_number:
                data["diversion_number"] = diversion_number

            msg = messaging.Message(
                data=data,
                android=messaging.AndroidConfig(priority="high"),
                token=token,
            )
            messaging.send(msg)
    except Exception as e:
        sys.stdout.write(f'VERBOSE "FCM notify error: {e}" 1\n')
        sys.stdout.flush()


if __name__ == "__main__":
    main()
