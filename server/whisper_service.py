#!/opt/vocaguard-venv/bin/python3
"""VocaGuard shared Whisper transcription service.

Loads the Whisper model ONCE and serves transcription for every call over a
Unix socket, so scam_detector_bg.py no longer loads a 1.4 GB model per call
(which OOM-capped the box at ~1 concurrent call and added ~2.7s load latency).

Protocol (both directions): 4-byte big-endian length prefix + pickled dict.
  request : {"audio": np.float32 array (16 kHz mono), "language": str|None}
  response: {"text": str, "language": str|None, "language_probability": float}

Concurrency: WHISPER_WORKERS (env, default 1) bounds simultaneous transcriptions.
Keep it at 1 on the current 2-core CPU (each transcription already uses both
cores); raise it on a bigger CPU / GPU host.
"""
import os, socket, struct, pickle, threading, logging
from faster_whisper import WhisperModel

SOCK_PATH = "/var/lib/asterisk/whisper.sock"  # asterisk-owned dir (service runs as asterisk)
WORKERS = int(os.environ.get("WHISPER_WORKERS", "1"))

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s whisper_service %(levelname)s %(message)s")
log = logging.getLogger("vocaguard.whisper")

log.info("loading Whisper 'medium' int8 (once)...")
model = WhisperModel("medium", device="cpu", compute_type="int8", local_files_only=True)
log.info("model loaded; workers=%d", WORKERS)
_sem = threading.Semaphore(WORKERS)


def _recvall(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("client closed connection")
        buf += chunk
    return buf


def _transcribe(audio, language):
    with _sem:
        segments, info = model.transcribe(
            audio, beam_size=5, best_of=3, vad_filter=True,
            vad_parameters=dict(min_silence_duration_ms=1000, speech_pad_ms=400),
            language=language,
        )
        text = " ".join(seg.text for seg in segments).strip()
        return {
            "text": text,
            "language": info.language,
            "language_probability": float(getattr(info, "language_probability", 0.0) or 0.0),
        }


def _handle(conn):
    try:
        n = struct.unpack(">I", _recvall(conn, 4))[0]
        req = pickle.loads(_recvall(conn, n))
        resp = _transcribe(req["audio"], req.get("language"))
    except Exception as e:
        log.warning("handle error: %s", e)
        resp = {"text": "", "language": None, "language_probability": 0.0, "error": str(e)}
    try:
        data = pickle.dumps(resp)
        conn.sendall(struct.pack(">I", len(data)) + data)
    except Exception:
        pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    if os.path.exists(SOCK_PATH):
        os.unlink(SOCK_PATH)
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(SOCK_PATH)
    os.chmod(SOCK_PATH, 0o666)  # allow the asterisk-user bg processes to connect
    srv.listen(64)
    log.info("listening on %s", SOCK_PATH)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=_handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
