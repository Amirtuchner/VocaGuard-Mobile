#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Transcribe the real scam recordings (Hebrew/Russian) with Whisper medium.
Handles unicode filenames; saves one transcript per file + a combined JSON."""
import os, json, whisper

SRC = r"C:\VocaGuard recordings"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "real_recordings")
os.makedirs(OUT, exist_ok=True)
EXTS = (".m4a", ".mp4", ".mp3", ".wav", ".ogg")

files = sorted(f for f in os.listdir(SRC) if f.lower().endswith(EXTS))
print(f"Found {len(files)} audio files", flush=True)

print("Loading Whisper 'medium' (one-time)...", flush=True)
model = whisper.load_model("medium")
print("Model loaded.", flush=True)

results = []
for i, f in enumerate(files, 1):
    path = os.path.join(SRC, f)
    print(f"[{i}/{len(files)}] transcribing: {f}", flush=True)
    try:
        r = model.transcribe(path, task="transcribe", fp16=False)
        text = (r.get("text") or "").strip()
        lang = r.get("language", "")
    except Exception as e:
        text, lang = f"<ERROR: {e}>", "err"
    with open(os.path.join(OUT, f"{i:02d}.txt"), "w", encoding="utf-8") as fh:
        fh.write(f"FILE: {f}\nLANG: {lang}\n\n{text}\n")
    results.append({"idx": i, "file": f, "lang": lang, "chars": len(text), "text": text})
    print(f"   -> lang={lang} chars={len(text)}", flush=True)

with open(os.path.join(OUT, "_all.json"), "w", encoding="utf-8") as fh:
    json.dump(results, fh, ensure_ascii=False, indent=2)
print("DONE", flush=True)
