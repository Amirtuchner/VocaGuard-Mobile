#!/opt/vocaguard-venv/bin/python3
"""HTTPS server on port 443.

Endpoints:
  POST /register          — register a new user (phone_number + fcm_token)
                            returns {sip_extension, sip_password, did_number}
  POST /register-token    — update FCM token for an existing user
                            body: JSON {phone_number, fcm_token}
  POST /accept-call       — AMI bridge: {channel, caller, phone_number}
  POST /hangup            — AMI hangup: {channel, phone_number}
"""
import ssl, json, re, socket, logging, sqlite3, secrets, subprocess
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

# ---------------------------------------------------------------------------
# Paths / constants
# ---------------------------------------------------------------------------
SECRET_FILE      = "/opt/vocaguard/token_server_secret.txt"
AMI_SECRET_FILE  = "/opt/vocaguard/ami_secret.txt"
CERT_FILE        = "/opt/vocaguard/server.crt"
KEY_FILE         = "/opt/vocaguard/server.key"
TOKEN_FILE       = "/opt/vocaguard/fcm_token.txt"  # single-user fallback
DB_PATH          = "/opt/vocaguard/users.db"
PJSIP_USERS_FILE = "/etc/asterisk/pjsip_users.conf"
DID_NUMBER       = "+97233741493"
_DID_CC = "972"

def _normalize_phone(number: str) -> str:
    n = number.strip().replace(" ", "").replace("-", "")
    n = n.lstrip("+")
    if n.startswith("00"):
        n = n[2:]
    if n.startswith("0") and not n.startswith(_DID_CC):
        n = _DID_CC + n[1:]
    return n

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

_CHANNEL_RE = re.compile(r"^PJSIP/[\w\-]{1,80}$")

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

def init_db():
    conn = sqlite3.connect(DB_PATH)
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
    conn.commit()
    conn.close()
    log.info("DB initialised at %s", DB_PATH)


def get_user_by_phone(phone: str) -> dict | None:
    conn = sqlite3.connect(DB_PATH)
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


def register_user(phone: str, fcm_token: str) -> dict:
    """Create user or update their FCM token. Returns SIP credentials."""
    conn = sqlite3.connect(DB_PATH)
    existing = conn.execute(
        "SELECT sip_extension, sip_password FROM users WHERE phone_number=?",
        (phone,)
    ).fetchone()

    if existing:
        conn.execute(
            "UPDATE users SET fcm_token=? WHERE phone_number=?",
            (fcm_token, phone)
        )
        conn.commit()
        conn.close()
        log.info("Updated FCM token for existing user %s", phone)
        return {
            "sip_extension": existing[0],
            "sip_password":  existing[1],
            "did_number":    DID_NUMBER,
        }

    # New user: assign next extension number
    row = conn.execute(
        "SELECT MAX(CAST(SUBSTR(sip_extension, 12) AS INTEGER)) FROM users "
        "WHERE sip_extension LIKE 'vocaguard__%'"
    ).fetchone()
    next_num  = (row[0] or 1000) + 1
    sip_ext   = f"vocaguard_{next_num}"
    sip_pass  = secrets.token_urlsafe(16)

    conn.execute(
        "INSERT INTO users (phone_number, fcm_token, sip_extension, sip_password) "
        "VALUES (?, ?, ?, ?)",
        (phone, fcm_token, sip_ext, sip_pass)
    )
    conn.commit()
    conn.close()

    provision_pjsip_user(sip_ext, sip_pass)
    log.info("Registered new user %s → extension %s", phone, sip_ext)

    return {
        "sip_extension": sip_ext,
        "sip_password":  sip_pass,
        "did_number":    DID_NUMBER,
    }


def update_fcm_token(phone: str, fcm_token: str) -> bool:
    """Update FCM token for an existing user. Returns False if user not found."""
    conn = sqlite3.connect(DB_PATH)
    cur = conn.execute(
        "UPDATE users SET fcm_token=? WHERE phone_number=?",
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
    conn = sqlite3.connect(DB_PATH)
    try:
        row = conn.execute(
            "SELECT sip_extension FROM users WHERE phone_number=?", (phone,)
        ).fetchone()
        if not row:
            return False
        ext = row[0]
        conn.execute("DELETE FROM users WHERE phone_number=?", (phone,))
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
        f"\n[auth_{ext}]\n"
        f"type=auth\n"
        f"auth_type=userpass\n"
        f"username={ext}\n"
        f"password={password}\n"
        f"\n[{ext}]\n"
        f"type=aor\n"
        f"max_contacts=1\n"
        f"remove_existing=yes\n"
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

def _ami_originate_safe(channel, caller, sip_ext, phone):
    """Wrapper that logs errors from ami_originate without crashing the server."""
    try:
        ami_originate(channel, caller, sip_ext, phone)
        log.info("Bridge originated: %s → %s (user=%s)", channel, sip_ext, phone)
    except Exception as e:
        log.error("ami_originate error: %s", e)


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):

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
                self._send(500, {"error": str(e)})

        elif self.path == "/register-token":
            # Accepts JSON {phone_number, fcm_token} or legacy raw token string
            try:
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length)
                try:
                    data  = json.loads(raw.decode())
                    phone = data.get("phone_number", "").strip()
                    fcm   = data.get("fcm_token", "").strip()
                    if phone and fcm:
                        if not update_fcm_token(phone, fcm):
                            # Phone not registered yet — save to file as fallback
                            log.warning("register-token: unknown phone %s, saving to file", phone)
                            with open(TOKEN_FILE, "w") as f:
                                f.write(fcm)
                    elif fcm:
                        with open(TOKEN_FILE, "w") as f:
                            f.write(fcm)
                except (json.JSONDecodeError, AttributeError):
                    # Legacy: raw token string
                    token = raw.decode().strip()
                    if token:
                        with open(TOKEN_FILE, "w") as f:
                            f.write(token)
                        log.info("Legacy token saved: %s...", token[:20])
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

                user    = get_user_by_phone(phone) if phone else None
                sip_ext = user["sip_extension"] if user else "vocaguard"  # single-user fallback

                # Respond 200 immediately so the Android client doesn't time out and
                # retry — a duplicate request would trigger a second AMI Originate and
                # a second SIP INVITE (causing a double-ring on the user's phone).
                self._send(200)
                import threading
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
                if not caregiver:
                    self._send(400, {"error": "caregiver_number required"})
                    return
                import threading, os
                caller_id = data.get("senior_number", "").strip() or TWILIO_NUMBER
                log.info("call_caregiver: senior_number=%r caller_id=%s", data.get("senior_number"), caller_id)
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
                        to_number = _normalize_phone(caregiver)
                        if not to_number.startswith("+"):
                            to_number = "+" + to_number
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
                self._send(500, {"error": str(e)})

        elif self.path == "/report-scam":
            try:
                data   = self._read_json()
                number = data.get("number", "").strip()
                stype  = data.get("type", "UNKNOWN").strip()
                reporter = data.get("reporter", "").strip()
                if not number:
                    self._send(400, {"error": "number required"})
                    return
                conn = sqlite3.connect(DB_PATH)
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
                self._send(500, {"error": str(e)})

        elif self.path == "/remove-scam":
            try:
                data   = self._read_json()
                number = data.get("number", "").strip()
                reporter = data.get("reporter", "").strip()
                if not number:
                    self._send(400, {"error": "number required"})
                    return
                conn = sqlite3.connect(DB_PATH)
                conn.execute(
                    "DELETE FROM community_blocklist WHERE phone_number = ?",
                    (number,)
                )
                conn.commit()
                changes = conn.total_changes
                conn.close()
                log.info("Scam removed: %s by=%s", number, reporter)
                self._send(200, {"status": "removed", "number": number})
            except Exception as e:
                log.error("remove-scam error: %s", e)
                self._send(500, {"error": str(e)})

        elif self.path == "/blocklist":
            try:
                conn = sqlite3.connect(DB_PATH)
                rows = conn.execute(
                    "SELECT phone_number, scam_type FROM community_blocklist"
                ).fetchall()
                conn.close()
                result = [{"number": r[0], "type": r[1]} for r in rows]
                self._send(200, result)
            except Exception as e:
                log.error("blocklist error: %s", e)
                self._send(500, {"error": str(e)})

        else:
            self._send(404)

    def do_GET(self):
        if self.path == "/blocklist":
            try:
                conn = sqlite3.connect(DB_PATH)
                rows = conn.execute(
                    "SELECT phone_number, scam_type FROM community_blocklist"
                ).fetchall()
                conn.close()
                result = [{"number": r[0], "type": r[1]} for r in rows]
                self._send(200, result)
            except Exception as e:
                log.error("blocklist error: %s", e)
                self._send(500, {"error": str(e)})
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
                self._send(500, {"error": str(e)})
        else:
            self._send(404)

    def log_message(self, fmt, *args):
        log.info("%s - - %s", self.address_string(), fmt % args)


class ReuseHTTPServer(ThreadingMixIn, HTTPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    init_db()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT_FILE, KEY_FILE)
    server = ReuseHTTPServer(("0.0.0.0", 443), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    log.info("Token server started on port 443 (multi-tenant)")
    server.serve_forever()
