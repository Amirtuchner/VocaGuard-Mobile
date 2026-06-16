#!/opt/vocaguard-venv/bin/python3
"""HTTPS server that receives the FCM device token from the app."""
import ssl
from http.server import HTTPServer, BaseHTTPRequestHandler

SECRET_FILE = "/opt/vocaguard/token_server_secret.txt"
CERT_FILE   = "/opt/vocaguard/server.crt"
KEY_FILE    = "/opt/vocaguard/server.key"
TOKEN_FILE  = "/opt/vocaguard/fcm_token.txt"

with open(SECRET_FILE) as f:
    EXPECTED_SECRET = f.read().strip()

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {EXPECTED_SECRET}":
            self.send_response(403)
            self.end_headers()
            return
        if self.path == "/register-token":
            length = int(self.headers.get("Content-Length", 0))
            token = self.rfile.read(length).decode().strip()
            if token:
                with open(TOKEN_FILE, "w") as f:
                    f.write(token)
                print(f"Token saved: {token[:20]}...", flush=True)
                self.send_response(200)
            else:
                self.send_response(400)
        else:
            self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        pass  # suppress access logs

class ReuseHTTPServer(HTTPServer):
    allow_reuse_address = True   # rebind immediately after restart/crash

if __name__ == "__main__":
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT_FILE, KEY_FILE)
    server = ReuseHTTPServer(("0.0.0.0", 443), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print("Token server running on port 443 (HTTPS)", flush=True)
    server.serve_forever()
