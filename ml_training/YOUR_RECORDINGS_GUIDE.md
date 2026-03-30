# Using Your Own Scam Call Recordings

Great! You have 3 real scam call recordings. Here's exactly how to use them for training.

## Complete Workflow

### Step 1: Prepare Your Audio Files

Place your 3 recordings in this folder:
```
ml_training/
├── recording1.mp3  ← Your first recording
├── recording2.mp3  ← Your second recording
└── recording3.mp3  ← Your third recording
```

Supported formats: MP3, WAV, M4A, OGG

---

### Step 2: Transcribe to Text

#### Option A: Use Whisper (Recommended - Free & Local)

```bash
# Install Whisper
pip install openai-whisper

# Transcribe each recording
python transcribe_audio.py recording1.mp3
python transcribe_audio.py recording2.mp3
python transcribe_audio.py recording3.mp3
```

This creates:
- `recording1_transcript.txt`
- `recording2_transcript.txt`
- `recording3_transcript.txt`

#### Option B: Manual Transcription (Most Accurate)

1. Listen to each recording
2. Type everything the **scammer** says
3. Save as `.txt` files

**Example transcript format:**
```
This is calling from Microsoft technical support. We have detected a virus on your computer. You need to download AnyDesk immediately so we can fix the problem. If you don't act now your files will be deleted. We need remote access to your computer to remove the malware.
```

**What to include:**
- ✓ Everything the scammer says
- ✓ Keep hesitations, repetitions (shows uncertainty)
- ✓ Keep filler words (um, uh, like)
- ✗ Skip your questions (unless they reveal scammer hesitation)

---

### Step 3: Identify Scam Types

For each recording, determine the scam type:

```
recording1.mp3 → Tech support scam → Label: 2
recording2.mp3 → Tech support scam → Label: 2
recording3.mp3 → IRS scam → Label: 1
```

**Label mapping:**
```
0 = Legitimate call
1 = IRS scam
2 = Tech support scam
3 = Bank fraud
4 = Lottery prize scam
5 = Social Security scam
6 = Robocall
7 = Phishing
8 = Insurance scam
9 = Donation fraud
```

---

### Step 4: Augment Your Data

3 recordings alone won't be enough. Let's expand them:

**Edit `augment_data.py` line 83:**

```python
recordings = [
    # (transcript_file, scam_type_label)
    ('recording1_transcript.txt', 2),  # Your first recording
    ('recording2_transcript.txt', 2),  # Your second recording
    ('recording3_transcript.txt', 1),  # Your third recording
]
```

**Run augmentation:**
```bash
python augment_data.py
```

This will:
1. Split each transcript into phrases
2. Create variations (change numbers, synonyms, etc.)
3. Generate 100-200 examples per recording
4. Combine with sample data
5. Save as `training_data.csv`

**Result:**
- Your 3 recordings → ~300-600 examples
- Sample data → ~40 examples
- **Total: ~340-640 training examples**

Still not ideal, but much better!

---

### Step 5: Train the Model

```bash
python train_model.py
```

This will:
- Load `training_data.csv`
- Train neural network
- Save `scam_detector.tflite`

**Expected accuracy with your data:**
- Overall: 70-80% (limited data)
- Better on your specific scam types
- May have false positives on other types

---

### Step 6: Deploy to App

```bash
# Copy model to app
cp scam_detector.tflite ../app/src/main/assets/

# Rebuild app in Android Studio
# Test with real calls!
```

---

## Important Notes

### Data Quality > Quantity

Your 3 **real** recordings are more valuable than 1000 synthetic examples because:
- ✓ Real scammer speech patterns
- ✓ Actual hesitations and lies
- ✓ Natural conversational flow
- ✓ Real-world audio quality

### Improving Over Time

**Initial model** (3 recordings):
- Works on similar scams
- May miss variations
- Some false positives

**After collecting more data:**
- Every new scam call = more training data
- Retrain model monthly
- Accuracy improves to 90%+

### Privacy & Legal

**Before using recordings:**
- ✓ Check if recording calls is legal in your region
- ✓ Only use recordings of actual scammers (not legitimate businesses)
- ✓ Remove personal information (your name, address, etc.) from transcripts
- ✓ Don't share recordings publicly without consent

---

## Example: Full Process

### Your Recording 1: Tech Support Scam

**Audio:**
```
recording1.mp3 (5 minutes, you asking questions, scammer hesitating)
```

**Transcript** (scammer's speech only):
```
Hello this is uh calling from Microsoft technical support.
We have detected uh some malware on your computer system.
You need to um download a program called AnyDesk so we can uh
access your computer remotely to fix this issue.
If you don't do this immediately your files will be deleted.
We need you to go to anydesk.com right now.
```

**After augmentation** (generates ~100 variations):
```
Hello this is calling from Windows technical support. We have detected some virus...
This is Microsoft support um your computer has malware you know...
Technical support here uh detected infection on your PC system...
... (97 more variations)
```

**Training:** All variations labeled as `2` (tech support scam)

---

## Troubleshooting

### "File not found" error

Make sure transcript files are named exactly:
```
recording1_transcript.txt
recording2_transcript.txt
recording3_transcript.txt
```

### Low transcription quality

Whisper might struggle with:
- Heavy accents
- Background noise
- Multiple speakers talking over each other

**Solution:** Manually correct the transcript after automatic transcription.

### Model overfits your data

If model only detects YOUR specific recordings:
- Add more diverse scam examples
- Use sample_training_data.csv
- Collect more recordings

### Can't identify scam type

Listen for keywords:
- "IRS", "taxes" → IRS scam (1)
- "computer", "virus", "anydesk" → Tech support (2)
- "bank", "account" → Bank fraud (3)
- "won", "lottery" → Lottery scam (4)

---

## Next Steps

1. **Start with your 3 recordings** as described above
2. **Test the model** on real calls
3. **Collect more data** over time:
   - Record more scam calls
   - Ask friends/family for their recordings
   - Find public datasets online
4. **Retrain periodically** as you get more data
5. **Monitor performance** and adjust

Your real recordings are a great start! The model will improve as you collect more data.