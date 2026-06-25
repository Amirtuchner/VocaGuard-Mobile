#!/opt/vocaguard-venv/bin/python3
"""Lightweight pre-answer AGI: sends FCM incoming_call alert immediately.
   Includes the Asterisk channel name so the app can signal Accept via AMI.
   Also clears any stale PJSIP/vocaguard-* channels so the bridge can succeed.
   Reads ORIGINAL_CALLED (from REDIRECTING(from-num)) to identify which user
   the call was originally for, then looks up their FCM token from the DB.
"""
import sys, os, json, socket, re, sqlite3
import firebase_admin
from firebase_admin import credentials, messaging

SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"   # single-user fallback
DB_PATH         = "/opt/vocaguard/users.db"
AMI_HOST        = "127.0.0.1"
AMI_PORT        = 5038
AMI_USER        = "vocaguard-bridge"
AMI_SECRET_FILE = "/opt/vocaguard/ami_secret.txt"


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


def _ami_recv(s: socket.socket) -> str:
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf.decode(errors="replace")


def cleanup_stale_vocaguard_channels():
    """Hang up any lingering PJSIP/vocaguard_*-* channels before bridging a new call."""
    try:
        ami_secret = open(AMI_SECRET_FILE).read().strip()
        s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=5)
        s.recv(1024)

        s.sendall(
            f"Action: Login\r\nUsername: {AMI_USER}\r\nSecret: {ami_secret}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        s.sendall(b"Action: CoreShowChannels\r\nActionID: cleanup\r\n\r\n")

        buf = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
            if b"CoreShowChannelsComplete" in buf:
                break

        text = buf.decode(errors="replace")

        stale = []
        for block in text.split("\r\n\r\n"):
            channel_line = next(
                (l for l in block.splitlines()
                 if l.startswith("Channel: PJSIP/vocaguard")),
                None
            )
            if channel_line:
                ch = channel_line.split(": ", 1)[1].strip()
                stale.append(ch)

        for ch in stale:
            s.sendall(f"Action: Hangup\r\nChannel: {ch}\r\n\r\n".encode())
            _ami_recv(s)

        s.sendall(b"Action: Logoff\r\n\r\n")
        s.close()

        if stale:
            sys.stdout.write(f'VERBOSE "Cleared {len(stale)} stale channel(s)" 1\n')
            sys.stdout.flush()
    except Exception as e:
        sys.stdout.write(f'VERBOSE "cleanup_stale warning: {e}" 1\n')
        sys.stdout.flush()


def main():
    agi_vars = agi_init()
    caller  = agi_vars.get("agi_callerid", "")
    channel = agi_vars.get("agi_channel", "")

    # Read original called number (set in extensions.conf from REDIRECTING(from-num))
    diversion_number = agi_get_variable("ORIGINAL_CALLED")

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
