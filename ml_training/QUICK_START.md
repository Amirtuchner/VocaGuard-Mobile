# Quick Start Guide - Train Your Model

You have 4 English scam call recordings ready to train! Follow these steps:

---

## Step 1: Install Dependencies

Open **Command Prompt** or **PowerShell** in this folder and run:

```bash
pip install -r requirements.txt
```

This installs:
- TensorFlow (AI framework)
- Whisper (speech-to-text)
- Other tools

**Time:** ~5 minutes

---

## Step 2: Transcribe Your Recordings

### Option A: Automatic (Batch)

**Windows:**
```bash
transcribe_all.bat
```

**Mac/Linux:**
```bash
bash transcribe_all.sh
```

This transcribes all 4 recordings automatically.

**Time:** 5-15 minutes (depends on computer speed)

### Option B: Manual (One by one)

```bash
python transcribe_audio.py recording1.mp3
python transcribe_audio.py recording2.mp3
python transcribe_audio.py recording3.mp3
python transcribe_audio.py recording4.mp3
```

Choose method 1 when prompted (Whisper).

---

## Step 3: Review Transcripts

After transcription, you'll have:
```
recording1_transcript.txt
recording2_transcript.txt
recording3_transcript.txt
recording4_transcript.txt
```

**Open each file** and check:
- ✓ Is the transcription accurate?
- ✓ Is it in English?
- ✓ Does it capture the scammer's speech?

**Fix errors manually** if needed (Whisper isn't perfect).

---

## Step 4: Identify Scam Types

For each recording, determine the scam type:

### Common Scam Types:

| Type | Label | Keywords |
|------|-------|----------|
| Tech Support | 2 | "computer", "virus", "Microsoft", "AnyDesk" |
| IRS | 1 | "IRS", "taxes", "arrest warrant" |
| Bank Fraud | 3 | "bank", "account", "suspended", "verify" |
| Lottery | 4 | "won", "prize", "lottery", "congratulations" |
| Social Security | 5 | "SSN", "social security", "suspended" |
| Robocall | 6 | "recorded message", "press 1" |
| Phishing | 7 | "verify", "confirm", "update information" |
| Insurance | 8 | "health insurance", "medicare", "qualify" |
| Donation | 9 | "charity", "donate", "help victims" |

### Example:
```
Recording 1: "Your computer has a virus..." → Tech Support (2)
Recording 2: "This is IRS..." → IRS Scam (1)
Recording 3: "Microsoft support calling..." → Tech Support (2)
Recording 4: "Your bank account..." → Bank Fraud (3)
```

---

## Step 5: Configure Scam Types

Edit `augment_data.py` (line 86-91):

```python
recordings = [
    ('recording1_transcript.txt', 2),  # Change 2 to your scam type
    ('recording2_transcript.txt', 2),  # Change 2 to your scam type
    ('recording3_transcript.txt', 2),  # Change 2 to your scam type
    ('recording4_transcript.txt', 2),  # Change 2 to your scam type
]
```

**Use any text editor** (Notepad, VS Code, etc.)

---

## Step 6: Generate Training Data

```bash
python augment_data.py
```

This will:
- Load your 4 transcripts
- Generate 100-200 variations per recording
- Combine with sample data
- Save to `training_data.csv`

**Output:** ~1,000-1,700 training examples

**Time:** 10-30 seconds

---

## Step 7: Train the Model

```bash
python train_model.py
```

This will:
- Load training data
- Train neural network (100 epochs)
- Convert to TensorFlow Lite
- Save as `scam_detector.tflite`

**Time:** 5-15 minutes

**Expected output:**
```
Training...
Epoch 1/100 ...
...
Test Accuracy: 78.5%
✓ Model saved to scam_detector.tflite
```

---

## Step 8: Deploy to App

### Copy model to app:

**Windows:**
```bash
copy scam_detector.tflite ..\app\src\main\assets\
```

**Mac/Linux:**
```bash
cp scam_detector.tflite ../app/src/main/assets/
```

### Rebuild app:
1. Open Android Studio
2. **Build → Rebuild Project**
3. Run on your device

---

## Step 9: Test!

1. Open VocaGuard app
2. Check logs: "ML model loaded successfully"
3. Receive a call
4. App should detect scams with AI!

---

## Troubleshooting

### "ModuleNotFoundError: No module named 'whisper'"
```bash
pip install openai-whisper
```

### "No such file: recording1.mp3"
Make sure you're in the `ml_training` folder:
```bash
cd C:\Users\userX\AndroidStudioProjects\VocaGuard\ml_training
```

### "File not found: recording1_transcript.txt"
Run transcription first (Step 2).

### Low accuracy (<70%)
- Your recordings might be too similar
- Add more diverse examples
- Check if transcripts are accurate

### Model file too large
- It's fine, TensorFlow Lite models are ~200-500KB
- If >1MB, check if training data is too large

---

## Summary

**Full workflow:**
```bash
# 1. Install
pip install -r requirements.txt

# 2. Transcribe
transcribe_all.bat

# 3. Edit augment_data.py (scam types)

# 4. Generate data
python augment_data.py

# 5. Train
python train_model.py

# 6. Deploy
copy scam_detector.tflite ..\app\src\main\assets\

# 7. Rebuild app in Android Studio
```

**Total time:** 30-60 minutes (mostly waiting for training)

---

## Your Setup

✓ 4 English recordings (1.6MB + 4.7MB + 16MB + 27MB)
✓ Estimated: 1,000-1,700 training examples
✓ Expected accuracy: 75-85%
✓ All tools ready to go!

**Ready to start? Run Step 1!**