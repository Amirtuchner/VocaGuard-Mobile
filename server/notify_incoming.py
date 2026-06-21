#!/opt/vocaguard-venv/bin/python3
"""Lightweight pre-answer AGI: sends FCM incoming_call alert immediately.
   Includes the Asterisk channel name so the app can signal Accept via AMI.
   Also clears any stale PJSIP/vocaguard-* channels so the bridge can succeed.
"""
import sys, os, json, socket
import firebase_admin
from firebase_admin import credentials, messaging

SERVICE_ACCOUNT = "/opt/vocaguard/service-account.json"
FCM_TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"
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


def _ami_recv(s: socket.socket) -> str:
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf.decode(errors="replace")


def cleanup_stale_vocaguard_channels():
    """Hang up any lingering PJSIP/vocaguard-* channels before we bridge a new call."""
    try:
        ami_secret = open(AMI_SECRET_FILE).read().strip()
        s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=5)
        s.recv(1024)  # banner

        # Login
        s.sendall(
            f"Action: Login\r\nUsername: {AMI_USER}\r\nSecret: {ami_secret}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        # Request channel list
        s.sendall(b"Action: CoreShowChannels\r\nActionID: cleanup\r\n\r\n")

        # Collect all events until CoreShowChannelsComplete
        buf = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
            if b"CoreShowChannelsComplete" in buf:
                break

        text = buf.decode(errors="replace")

        # Parse channel names from events
        stale = []
        for block in text.split("\r\n\r\n"):
            channel_line = next(
                (l for l in block.splitlines() if l.startswith("Channel: PJSIP/vocaguard-")),
                None
            )
            if channel_line:
                ch = channel_line.split(": ", 1)[1].strip()
                stale.append(ch)

        # Hang up each stale channel
        for ch in stale:
            s.sendall(
                f"Action: Hangup\r\nChannel: {ch}\r\n\r\n".encode()
            )
            _ami_recv(s)

        s.sendall(b"Action: Logoff\r\n\r\n")
        s.close()

        if stale:
            sys.stdout.write(f'VERBOSE "Cleared {len(stale)} stale vocaguard channel(s): {stale}" 1\n')
            sys.stdout.flush()
    except Exception as e:
        sys.stdout.write(f'VERBOSE "cleanup_stale warning: {e}" 1\n')
        sys.stdout.flush()


def main():
    agi_vars = agi_init()
    caller  = agi_vars.get("agi_callerid", "")
    channel = agi_vars.get("agi_channel", "")

    cleanup_stale_vocaguard_channels()

    try:
        cred = credentials.Certificate(SERVICE_ACCOUNT)
        firebase_admin.initialize_app(cred)
        token = open(FCM_TOKEN_FILE).read().strip()
        if token:
            msg = messaging.Message(
                data={
                    "type":             "incoming_call",
                    "caller_number":    caller,
                    "asterisk_channel": channel,
                },
                android=messaging.AndroidConfig(priority="high"),
                token=token,
            )
            messaging.send(msg)
    except Exception as e:
        sys.stdout.write(f'VERBOSE "FCM notify error: {e}" 1\n')
        sys.stdout.flush()

if __name__ == "__main__":
    main()
