#!/usr/bin/env python3
"""Measure real detection latency on the server: faster-whisper 'medium' int8
transcription of one 8s chunk, using the exact production params. Run on server."""
import sys, time
from faster_whisper import WhisperModel

wav = sys.argv[1]
t0 = time.time()
m = WhisperModel("medium", device="cpu", compute_type="int8", local_files_only=True)
load_t = time.time() - t0
print(f"Whisper 'medium' int8 model load: {load_t:.1f}s (one-time, at call start)")

times = []
for i in range(3):
    t = time.time()
    segs, info = m.transcribe(
        wav, beam_size=5, best_of=3, vad_filter=True,
        vad_parameters=dict(min_silence_duration_ms=1000, speech_pad_ms=400),
        language="en",
    )
    txt = " ".join(s.text for s in segs).strip()
    dt = time.time() - t
    times.append(dt)
    print(f"  run {i+1}: {dt:.2f}s   lang={info.language}   text='{txt[:70]}'")

avg = sum(times) / len(times)
print(f"\nAvg transcription of one 8s chunk: {avg:.2f}s on 2 CPU cores")
print("End-to-end latency after a scam phrase is spoken:")
print(f"  ~= 8s chunk window + {avg:.1f}s transcription + <0.01s scoring  ~= {8+avg:.1f}s")
