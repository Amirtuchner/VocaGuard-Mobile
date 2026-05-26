#!/opt/vocaguard-venv/bin/python3
import sys, os

LOG = "/var/log/vocaguard_debug.log"

def agi_init():
    while True:
        line = sys.stdin.readline()
        if line.strip() == "":
            break

def log(msg):
    with open(LOG, "a") as f:
        f.write(msg + "\n")
        f.flush()

agi_init()
log("=== debug_audio.py started ===")

audio_fd = 3
total = 0
try:
    for i in range(20):
        data = os.read(audio_fd, 3200)
        if not data:
            log(f"Chunk {i}: empty read")
            break
        log(f"Chunk {i}: {len(data)} bytes | hex={data[:16].hex()} | min={min(data)} max={max(data)}")
        total += len(data)
except Exception as e:
    log(f"Error reading fd: {e}")

log(f"Done. Total bytes: {total}")
