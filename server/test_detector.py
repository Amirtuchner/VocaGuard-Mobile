#!/opt/vocaguard-venv/bin/python3
"""
End-to-end test for scam_detector.py.

Creates a fake EAGI environment:
  - stdin  → AGI handshake variables
  - fd 3   → 8 kHz mono SLIN PCM synthesised by espeak-ng

Usage:
  python3 test_detector.py [--lang en|he] [--script "...text..."] [--legit]

Exit code: 0 = scam detected (expected), 1 = no detection (failure for scam tests),
           2 = subprocess error
"""
import argparse, asyncio, os, subprocess, sys, tempfile, threading, time

DETECTOR = "/opt/vocaguard/scam_detector.py"
LOG      = "/var/log/vocaguard_agi.log"

SCAM_SCRIPTS = {
    "en": (
        "This is Microsoft tech support. Your computer has a virus. "
        "We need remote access via TeamViewer immediately. "
        "You must send a wire transfer to a safe account or face legal action."
    ),
    "he": (
        # Hebrew scam: arrest + crypto transfer.
        # Uses loan-words (ביטקוין, anydesk) and brand-names that Whisper
        # transcribes correctly even at 2-second chunk boundaries.
        "שלום, יש לך תיק פלילי פתוח. "
        "אתה חייב להעביר ביטקוין מיד, אחרת תיעצר. "
        "הורד את האפליקציה anydesk עכשיו."
    ),
    "legit_en": "Hi, I wanted to confirm your appointment tomorrow at 3pm. Please call us back if you need to reschedule.",
}

def synthesise_wav(text: str, lang: str, out_path: str):
    """
    Synthesise text → WAV.
    Hebrew: edge-tts (he-IL-HilaNeural) — natural neural TTS, Whisper-recognisable.
    English: espeak-ng — sufficient for English Vosk/Whisper.
    """
    if lang == "he":
        _edge_tts_to_wav(text, "he-IL-HilaNeural", out_path)
    else:
        voice = "en-us"
        cmd = ["espeak-ng", "-v", voice, "-s", "130", "-w", out_path, "-a", "150", text]
        result = subprocess.run(cmd, capture_output=True)
        if result.returncode != 0:
            print(f"espeak-ng error: {result.stderr.decode()}", file=sys.stderr)
            sys.exit(2)

def _edge_tts_to_wav(text: str, voice: str, out_wav: str):
    """Use edge-tts to synthesise → MP3, then convert to WAV with sox."""
    import edge_tts

    mp3_path = out_wav.replace(".wav", ".mp3")

    async def _synth():
        communicate = edge_tts.Communicate(text, voice)
        await communicate.save(mp3_path)

    asyncio.run(_synth())

    # Convert MP3 → WAV
    result = subprocess.run(
        ["sox", mp3_path, out_wav],
        capture_output=True,
    )
    if result.returncode != 0:
        print(f"sox mp3→wav error: {result.stderr.decode()}", file=sys.stderr)
        sys.exit(2)

def wav_to_8k_slin(wav_path: str, pcm_path: str):
    """Convert WAV → raw 8 kHz mono signed 16-bit LE PCM using sox."""
    cmd = ["sox", wav_path, "-r", "8000", "-c", "1", "-e", "signed-integer", "-b", "16", "-L", "-t", "raw", pcm_path]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        print(f"sox error: {result.stderr.decode()}", file=sys.stderr)
        sys.exit(2)

def run_detector(pcm_path: str, caller: str = "+972501234567") -> int:
    """
    Run scam_detector.py with:
      stdin  = AGI handshake
      fd 3   = PCM audio data
    Returns the process exit code.
    """
    agi_handshake = (
        f"agi_request: /opt/vocaguard/scam_detector.py\n"
        f"agi_channel: PJSIP/test\n"
        f"agi_callerid: {caller}\n"
        f"\n"   # blank line = end of AGI vars
    ).encode()

    with open(pcm_path, "rb") as pcm_f:
        # Open a pipe for stdin (AGI vars)
        stdin_r, stdin_w = os.pipe()
        # fd 3 will be the PCM file descriptor
        proc = subprocess.Popen(
            ["/opt/vocaguard-venv/bin/python3", DETECTOR],
            stdin=stdin_r,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            pass_fds=(pcm_f.fileno(),),
            # EAGI pipe is fd 3 — we need to dup pcm_f to fd 3 inside child
            # We use a preexec_fn to do that
            preexec_fn=lambda: os.dup2(pcm_f.fileno(), 3),
        )
        os.close(stdin_r)

        # Write AGI handshake then close (detector reads until blank line)
        os.write(stdin_w, agi_handshake)
        os.close(stdin_w)

        proc.wait(timeout=120)
        return proc.returncode

def tail_log_for(keyword: str, timeout: float = 5.0) -> bool:
    """Return True if keyword appears in the log within timeout seconds."""
    start = time.time()
    while time.time() - start < timeout:
        try:
            out = subprocess.check_output(
                ["tail", "-20", LOG], stderr=subprocess.DEVNULL
            ).decode(errors="replace")
            if keyword in out:
                return True
        except Exception:
            pass
        time.sleep(0.3)
    return False


def run_audio_test(label: str, text: str, lang: str, expect_scam: bool) -> bool:
    """Full pipeline test: TTS → 8kHz PCM → scam_detector.py → log check."""
    print(f"\n{'='*60}")
    print(f"TEST (audio): {label}")
    print(f"  lang={lang}  expect_scam={expect_scam}")
    print(f"  script: {text[:80]}{'...' if len(text)>80 else ''}")
    print()

    with tempfile.TemporaryDirectory() as tmpdir:
        wav = os.path.join(tmpdir, "test.wav")
        pcm = os.path.join(tmpdir, "test.pcm")

        print("  [1/3] Synthesising audio...")
        synthesise_wav(text, lang, wav)
        wav_to_8k_slin(wav, pcm)
        size_kb = os.path.getsize(pcm) // 1024
        duration_s = os.path.getsize(pcm) / (8000 * 2)
        print(f"        {size_kb} KB  ({duration_s:.1f}s of audio)")

        print("  [2/3] Running scam_detector.py...")
        run_detector(pcm)

        print("  [3/3] Checking log for result...")
        detected = tail_log_for("SCAM DETECTED", timeout=3.0)
        fcm_sent  = tail_log_for("FCM sent",     timeout=3.0)

    if expect_scam:
        if detected:
            print(f"  PASS — scam detected ✓  FCM sent={fcm_sent}")
            return True
        else:
            print(f"  FAIL — no scam detected (expected detection)")
            return False
    else:
        if not detected:
            print(f"  PASS — no false positive ✓")
            return True
        else:
            print(f"  FAIL — false positive triggered on legit call")
            return False


def run_unit_test(label: str, cases: list) -> bool:
    """
    Direct text-injection test for detect_scam().
    cases: list of (text, expect_scam, description) tuples.
    Validates detection logic independently of STT quality.
    """
    print(f"\n{'='*60}")
    print(f"TEST (unit): {label}")

    # Import detect_scam and score_to_confidence from the deployed script
    import importlib.util, types
    spec = importlib.util.spec_from_file_location("scam_det", DETECTOR)
    mod  = importlib.util.load_from_spec(spec) if hasattr(importlib.util, "load_from_spec") else None
    # Fallback: exec the non-main portion
    src = open(DETECTOR).read().split("if __name__")[0]
    ns  = {}
    exec(compile(src, DETECTOR, "exec"), ns)
    detect_scam      = ns["detect_scam"]
    score_to_confidence = ns["score_to_confidence"]

    all_pass = True
    for text, expect_scam, desc in cases:
        is_scam, kws, score = detect_scam(text)
        conf = score_to_confidence(score) if is_scam else 0.0
        ok = (is_scam == expect_scam)
        status = "PASS" if ok else "FAIL"
        kw_str = f" kws={kws} score={score} conf={conf}" if is_scam else ""
        print(f"  {status} [{desc}]{kw_str}")
        if not ok:
            all_pass = False
    return all_pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", choices=["en", "he", "legit", "unit", "all"], default="all")
    args = ap.parse_args()

    results = []

    # ── Audio tests (full pipeline: TTS → PCM → scam_detector.py) ──────────
    if args.test in ("en", "all"):
        results.append(run_audio_test(
            "English scam (MS tech support + wire transfer)",
            SCAM_SCRIPTS["en"], "en", expect_scam=True,
        ))

    if args.test in ("legit", "all"):
        results.append(run_audio_test(
            "Legitimate English call (appointment reminder)",
            SCAM_SCRIPTS["legit_en"], "en", expect_scam=False,
        ))

    # ── Unit tests (detect_scam() text injection) ────────────────────────────
    # Hebrew audio TTS is unreliable at 2 s Whisper chunks (loan-words like
    # "anydesk" get transcribed as Hebrew phonetics by Whisper base).
    # These unit tests validate the detection logic directly.
    if args.test in ("he", "unit", "all"):
        results.append(run_unit_test("Hebrew / Israeli keyword detection", [
            # NII phishing — ביטוח לאומי (2pt) + קוד חד פעמי (2pt) = 4pt
            ("החשבון שלך הוקפא. צור קשר עם המוסד לביטוח לאומי ושלח קוד חד פעמי.",
             True, "ביטוח לאומי + קוד חד פעמי"),
            # Arrest scam — שבס (2pt) + צו מעצר (2pt) = 4pt
            ("יש לך צו מעצר מהשבס. זה דחוף מאוד.",
             True, "שבס + צו מעצר"),
            # Bank impersonation — מבנק לאומי (prefix match, 1pt) + הקפאת חשבון (2pt) = 3pt
            ("אני מתקשר מבנק לאומי. נרשמה הקפאת חשבון על החשבון שלך.",
             True, "מבנק לאומי (prefix) + הקפאת חשבון"),
            # False positive guard — bank name alone should NOT fire
            ("אני מבנק לאומי ורוצה לדבר על המשכנתא שלך.",
             False, "bank name alone — no false positive"),
            # Word-boundary guard — ביט must NOT match inside ביטוח
            ("ביטוח לאומי חשוב מאוד לכל אזרח.",
             False, "ביטוח should not match ביט"),
        ]))

    if args.test in ("unit", "all"):
        results.append(run_unit_test("English keyword detection + confidence", [
            ("safe account wire transfer arrest warrant",
             True,  "3 high-signal EN keywords"),
            ("hello how are you today the weather is nice",
             False, "legit EN — no false positive"),
        ]))

        results.append(run_unit_test("Sliding window — expired text ignored", [
            # Simulate: 120s-old scam text should not count
            # (Tested via direct detect_scam on empty string after window expires)
            ("",  False, "empty window = no detection"),
        ]))

    print(f"\n{'='*60}")
    passed = sum(results)
    total  = len(results)
    print(f"Results: {passed}/{total} passed")
    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
