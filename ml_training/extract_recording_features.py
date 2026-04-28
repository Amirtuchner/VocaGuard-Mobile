#!/usr/bin/env python3
"""
extract_recording_features.py

Transcribes audio recordings with Whisper, extracts all 42-feature-vector
signals, and appends rows to training_data.csv.

Usage:
    python extract_recording_features.py <audio_file> [<audio_file> ...] --label <int>

Labels:
    0  Legitimate   1  IRS scam      2  Tech support  3  Bank fraud
    4  Lottery      5  Social Sec.   6  Robocall      7  Phishing
    8  Insurance    9  Investment    10 Donation fraud
"""

import argparse
import csv
import math
import os
import re
import sys
import numpy as np
import whisper
from collections import Counter
from pydub import AudioSegment

# Force UTF-8 output on Windows so non-ASCII transcript text doesn't crash prints
if sys.stdout.encoding != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── constants matching CallMonitoringService / TextPreprocessor ───────────────
SILENCE_THRESHOLD_RMS = 0.02      # normalised RMS (0-1) below which = silent
NOISE_FLOOR_MIN       = 0.015     # above this but below silence = noise floor
LONG_SILENCE_FRAMES   = 30        # consecutive silent frames → long silence
SPEAKER_SWITCH_DB     = 3.0       # dB RMS difference between segments → switch
MIN_SEGMENT_FRAMES    = 5         # minimum frames for a valid speech segment
FRAME_MS              = 20        # analysis frame size in milliseconds
DTMF_SAMPLE_RATE      = 16_000    # Hz
DTMF_ROW_FREQS        = [697, 770, 852, 941]
DTMF_COL_FREQS        = [1209, 1336, 1477, 1633]
DTMF_THRESHOLD_RATIO  = 3.0

CSV_PATH = os.path.join(os.path.dirname(__file__), "training_data.csv")
CSV_COLUMNS = [
    "text", "label",
    "avg_rms", "rms_std_dev", "silence_ratio", "had_long_silence",
    "call_duration_sec", "call_hour",
    "speaker_switches", "noise_floor_db", "speech_rate_wpm", "dtmf_detected",
]


# ── audio helpers ─────────────────────────────────────────────────────────────

def load_mono_pcm(path: str, target_sr: int = 16_000) -> tuple[np.ndarray, int]:
    """Load any audio file via pydub, return (float32 array [-1,1], sample_rate)."""
    audio = AudioSegment.from_file(path)
    audio = audio.set_frame_rate(target_sr).set_channels(1).set_sample_width(2)
    samples = np.frombuffer(audio.raw_data, dtype=np.int16).astype(np.float32) / 32768.0
    return samples, target_sr


def rms_frames(samples: np.ndarray, sr: int) -> np.ndarray:
    """Split samples into FRAME_MS frames and return per-frame RMS array."""
    frame_len = sr * FRAME_MS // 1000
    n_frames = len(samples) // frame_len
    frames = samples[:n_frames * frame_len].reshape(n_frames, frame_len)
    return np.sqrt(np.mean(frames ** 2, axis=1))


# ── feature extractors ────────────────────────────────────────────────────────

def extract_audio_features(samples: np.ndarray, sr: int) -> dict:
    rms = rms_frames(samples, sr)
    total_frames = len(rms)
    if total_frames == 0:
        return {k: 0.0 for k in [
            "avg_rms", "rms_std_dev", "silence_ratio", "had_long_silence",
            "call_duration_sec", "speaker_switches", "noise_floor_db", "dtmf_detected"
        ]}

    silent = rms < SILENCE_THRESHOLD_RMS
    speech_rms = rms[~silent]

    avg_rms    = float(np.mean(speech_rms) * 20) if len(speech_rms) else 0.0  # scale to dB-like
    rms_std    = float(np.std(speech_rms)  * 5)  if len(speech_rms) else 0.0
    silence_ratio = float(np.sum(silent) / total_frames)
    duration_sec  = len(samples) / sr

    # long silence
    had_long_silence = False
    consec = 0
    for s in silent:
        if s:
            consec += 1
            if consec >= LONG_SILENCE_FRAMES:
                had_long_silence = True
                break
        else:
            consec = 0

    # noise floor (quiet-but-not-zero frames)
    noise_mask = (rms >= NOISE_FLOOR_MIN) & silent
    noise_floor_db = float(np.mean(rms[noise_mask]) * 20) if np.any(noise_mask) else 0.0

    # speaker switch detection
    speaker_switches = 0
    in_speech = False
    prev_seg_avg = -1.0
    cur_seg = []
    for r, s in zip(rms, silent):
        if not s:  # speech
            if not in_speech:
                in_speech = True
            cur_seg.append(float(r))
        else:      # silence
            if in_speech:
                in_speech = False
                if len(cur_seg) >= MIN_SEGMENT_FRAMES:
                    seg_avg = np.mean(cur_seg)
                    if prev_seg_avg >= 0:
                        diff_db = abs(20 * math.log10(max(seg_avg, 1e-9)) -
                                      20 * math.log10(max(prev_seg_avg, 1e-9)))
                        if diff_db > SPEAKER_SWITCH_DB:
                            speaker_switches += 1
                    prev_seg_avg = seg_avg
                cur_seg = []

    # DTMF detection (Goertzel on 40 ms windows)
    dtmf_detected = False
    frame_len = sr * 40 // 1000
    for start in range(0, len(samples) - frame_len, frame_len):
        window = (samples[start:start + frame_len] * 32768).astype(np.int16)
        if _detect_dtmf(window, sr):
            dtmf_detected = True
            break

    return {
        "avg_rms":          round(avg_rms, 4),
        "rms_std_dev":      round(rms_std, 4),
        "silence_ratio":    round(silence_ratio, 4),
        "had_long_silence": int(had_long_silence),
        "call_duration_sec": round(duration_sec, 2),
        "speaker_switches": speaker_switches,
        "noise_floor_db":   round(noise_floor_db, 4),
        "dtmf_detected":    int(dtmf_detected),
    }


def _goertzel_power(samples: np.ndarray, freq: float, sr: int) -> float:
    n = len(samples)
    k = int(0.5 + n * freq / sr)
    omega = 2.0 * math.pi * k / n
    coeff = 2.0 * math.cos(omega)
    q1 = q2 = 0.0
    for s in samples:
        q0 = coeff * q1 - q2 + float(s)
        q2, q1 = q1, q0
    return q1 * q1 + q2 * q2 - q1 * q2 * coeff


def _detect_dtmf(samples: np.ndarray, sr: int) -> bool:
    all_freqs = DTMF_ROW_FREQS + DTMF_COL_FREQS
    powers = [_goertzel_power(samples, f, sr) for f in all_freqs]
    mean_p = sum(powers) / len(powers)
    if mean_p == 0:
        return False
    max_row = max(powers[:4])
    max_col = max(powers[4:])
    return max_row > mean_p * DTMF_THRESHOLD_RATIO and max_col > mean_p * DTMF_THRESHOLD_RATIO


# ── text feature helpers (mirror train_model.py) ─────────────────────────────

def _repetition_score(text):
    words = [w for w in text.split() if len(w) > 2]
    if len(words) < 3:
        return 0.0
    trigrams = [f"{words[i]} {words[i+1]} {words[i+2]}" for i in range(len(words) - 2)]
    return min((len(trigrams) - len(set(trigrams))) / len(trigrams), 1.0)


def speech_rate_wpm(transcript: str, duration_sec: float) -> float:
    if duration_sec < 6:
        return 0.0
    words = [w for w in transcript.split() if w]
    return round(len(words) / (duration_sec / 60.0), 2)


# ── main ──────────────────────────────────────────────────────────────────────

def process_file(path: str, label: int, model) -> dict:
    print(f"\n  Loading audio: {os.path.basename(path)}")
    samples, sr = load_mono_pcm(path)

    print(f"  Transcribing ({len(samples)/sr:.1f}s)…")
    result = model.transcribe(path, fp16=False)
    transcript = result["text"].strip()
    print(f"  Transcript: {transcript[:120]}{'…' if len(transcript) > 120 else ''}")

    print("  Extracting audio features…")
    audio_feats = extract_audio_features(samples, sr)

    wpm = speech_rate_wpm(transcript, audio_feats["call_duration_sec"])

    return {
        "text":             transcript,
        "label":            label,
        "avg_rms":          audio_feats["avg_rms"],
        "rms_std_dev":      audio_feats["rms_std_dev"],
        "silence_ratio":    audio_feats["silence_ratio"],
        "had_long_silence": audio_feats["had_long_silence"],
        "call_duration_sec": audio_feats["call_duration_sec"],
        "call_hour":        12,   # unknown — use midday default
        "speaker_switches": audio_feats["speaker_switches"],
        "noise_floor_db":   audio_feats["noise_floor_db"],
        "speech_rate_wpm":  wpm,
        "dtmf_detected":    audio_feats["dtmf_detected"],
    }


def append_to_csv(rows: list[dict]):
    file_exists = os.path.isfile(CSV_PATH)
    with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        if not file_exists:
            writer.writeheader()
        # ensure existing file has header — check first line
        writer.writerows(rows)
    print(f"\n  Appended {len(rows)} row(s) to {CSV_PATH}")


def main():
    parser = argparse.ArgumentParser(description="Extract features from call recordings")
    parser.add_argument("files", nargs="+", help="Audio file paths")
    parser.add_argument("--label", type=int, required=True, help="Scam label (0-10)")
    parser.add_argument("--model", default="base", help="Whisper model size (tiny/base/small)")
    args = parser.parse_args()

    print(f"Loading Whisper '{args.model}' model…")
    whisper_model = whisper.load_model(args.model)

    rows = []
    for path in args.files:
        if not os.path.isfile(path):
            print(f"  WARNING: file not found, skipping: {path}")
            continue
        row = process_file(path, args.label, whisper_model)
        rows.append(row)
        print(f"  Features: duration={row['call_duration_sec']}s  wpm={row['speech_rate_wpm']}"
              f"  switches={row['speaker_switches']}  noise={row['noise_floor_db']}"
              f"  dtmf={row['dtmf_detected']}")

    if rows:
        append_to_csv(rows)
        print("\nDone. Run train_model.py to retrain.")
    else:
        print("\nNo files processed.")


if __name__ == "__main__":
    main()
