#!/opt/vocaguard-venv/bin/python3
"""HTTPS server on port 443 that handles two endpoints:
   POST /register-token  — saves the FCM device token from the app
   POST /accept-call     — signals Asterisk via AMI to bridge the user's SIP
                           phone into the waiting incoming call
"""
import ssl, json, re, socket
from http.server import HTTPServer, BaseHTTPRequestHandler

SECRET_FILE     = "/opt/vocaguard/token_server_secret.txt"
AMI_SECRET_FILE = "/opt/vocaguard/ami_secret.txt"
CERT_FILE       = "/opt/vocaguard/server.crt"
KEY_FILE        = "/opt/vocaguard/server.key"
TOKEN_FILE      = "/opt/vocaguard/fcm_token.txt"

AMI_HOST = "127.0.0.1"
AMI_PORT = 5038
AMI_USER = "vocaguard-bridge"

# Channel names look like PJSIP/d60n8HovalTll3mH-00000001
_CHANNEL_RE = re.compile(r"^PJSIP/[\w\-]{1,80}$")

with open(SECRET_FILE) as f:
    EXPECTED_SECRET = f.read().strip()

with open(AMI_SECRET_FILE) as f:
    AMI_SECRET = f.read().strip()


def _ami_recv(s: socket.socket) -> str:
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf.decode(errors="replace")


def ami_originate(orig_channel: str, orig_caller: str):
    """Use AMI to originate a call to PJSIP/vocaguard (Linphone) and bridge it
    with the waiting incoming scammer channel."""

    s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=5)
    try:
        s.recv(1024)  # banner line

        s.sendall(
            f"Action: Login\r\n"
            f"Username: {AMI_USER}\r\n"
            f"Secret: {AMI_SECRET}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        # Mark the incoming channel as accepted so dialplan skips EAGI fallback.
        s.sendall(
            f"Action: Setvar\r\n"
            f"Channel: {orig_channel}\r\n"
            f"Variable: VG_ACCEPTED\r\n"
            f"Value: 1\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        s.sendall(
            f"Action: Originate\r\n"
            f"Channel: PJSIP/vocaguard\r\n"
            f"Context: vocaguard-user\r\n"
            f"Exten: s\r\n"
            f"Priority: 1\r\n"
            f"Variable: ORIG_CHANNEL={orig_channel}\r\n"
            f"Variable: ORIG_CALLER={orig_caller}\r\n"
            f"Variable: PJSIP_HEADER(add,Call-Info)=<urn:ietf:params:answer-after=0>\r\n"
            f"CallerID: {orig_caller}\r\n"
            f"Timeout: 30000\r\n"
            f"Async: yes\r\n\r\n"
            .encode()
        )
        resp = _ami_recv(s)

        s.sendall(b"Action: Logoff\r\n\r\n")
    finally:
        s.close()

    if "Response: Success" not in resp and "Response: Error" not in resp:
        raise RuntimeError(f"AMI unexpected response: {resp[:120]}")
    if "Response: Error" in resp:
        raise RuntimeError(f"AMI error: {resp[:200]}")


def ami_hangup(channel: str):
    """Hang up the given channel AND any active PJSIP/vocaguard-* channels (Linphone leg)."""
    s = socket.create_connection((AMI_HOST, AMI_PORT), timeout=5)
    try:
        s.recv(1024)  # banner line

        s.sendall(
            f"Action: Login\r\n"
            f"Username: {AMI_USER}\r\n"
            f"Secret: {AMI_SECRET}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        # Hang up the original incoming channel (scammer side)
        s.sendall(
            f"Action: Hangup\r\n"
            f"Channel: {channel}\r\n\r\n"
            .encode()
        )
        _ami_recv(s)

        # Also list and hang up any active Linphone (vocaguard endpoint) channels
        s.sendall(b"Action: CoreShowChannels\r\n\r\n")
        raw = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            raw += chunk
            if b"EventList: Complete" in raw or b"\r\n\r\n" in raw[-20:]:
                break
        for line in raw.decode(errors="replace").splitlines():
            if line.startswith("Channel: PJSIP/vocaguard-"):
                ch = line.split(": ", 1)[1].strip()
                s.sendall(
                    f"Action: Hangup\r\n"
                    f"Channel: {ch}\r\n\r\n"
                    .encode()
                )
                _ami_recv(s)

        s.sendall(b"Action: Logoff\r\n\r\n")
    finally:
        s.close()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {EXPECTED_SECRET}":
            self.send_response(403)
            self.end_headers()
            return

        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)

        if self.path == "/register-token":
            token = body.decode().strip()
            if token:
                with open(TOKEN_FILE, "w") as f:
                    f.write(token)
                print(f"Token saved: {token[:20]}...", flush=True)
                self.send_response(200)
            else:
                self.send_response(400)

        elif self.path == "/accept-call":
            try:
                data    = json.loads(body.decode())
                channel = data.get("channel", "")
                caller  = data.get("caller", "")
                if not _CHANNEL_RE.match(channel):
                    self.send_response(400)
                    self.end_headers()
                    return
                ami_originate(channel, caller)
                print(f"Bridge originated: {channel}", flush=True)
                self.send_response(200)
            except Exception as e:
                print(f"accept-call error: {e}", flush=True)
                self.send_response(500)

        elif self.path == "/hangup":
            try:
                data    = json.loads(body.decode())
                channel = data.get("channel", "")
                if not _CHANNEL_RE.match(channel):
                    self.send_response(400)
                    self.end_headers()
                    return
                ami_hangup(channel)
                print(f"Hangup: {channel}", flush=True)
                self.send_response(200)
            except Exception as e:
                print(f"hangup error: {e}", flush=True)
                self.send_response(500)

        else:
            self.send_response(404)

        self.end_headers()

    def log_message(self, format, *args):
        pass  # suppress access logs


class ReuseHTTPServer(HTTPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT_FILE, KEY_FILE)
    server = ReuseHTTPServer(("0.0.0.0", 443), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print("Token server running on port 443 (HTTPS)", flush=True)
    server.serve_forever()
