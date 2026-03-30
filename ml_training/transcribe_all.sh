#!/bin/bash
# Transcribe all recordings at once

echo "VocaGuard - Batch Transcription"
echo "================================"
echo ""
echo "This will transcribe all 9 recordings using Whisper."
echo "Estimated time: 15-30 minutes depending on your computer."
echo ""

# Check if Whisper is installed
if ! python -c "import whisper" 2>/dev/null; then
    echo "Installing Whisper..."
    pip install openai-whisper
fi

# Transcribe each recording
echo ""
echo "[1/9] Transcribing recording1.mp3..."
python transcribe_audio.py recording1.mp3

echo ""
echo "[2/9] Transcribing recording2.mp3..."
python transcribe_audio.py recording2.mp3

echo ""
echo "[3/9] Transcribing recording3.mp3..."
python transcribe_audio.py recording3.mp3

echo ""
echo "[4/9] Transcribing recording4.mp3..."
python transcribe_audio.py recording4.mp3

echo ""
echo "[5/9] Transcribing recording5.mp4..."
python transcribe_audio.py recording5.mp4

echo ""
echo "[6/9] Transcribing recording6.mp4..."
python transcribe_audio.py recording6.mp4

echo ""
echo "[7/9] Transcribing recording7.mp4..."
python transcribe_audio.py recording7.mp4

echo ""
echo "[8/9] Transcribing recording8.mp4..."
python transcribe_audio.py recording8.mp4

echo ""
echo "[9/9] Transcribing recording9.mp4..."
python transcribe_audio.py recording9.mp4

echo ""
echo "✓ All 9 recordings transcribed!"
echo ""
echo "Next steps:"
echo "1. Review the transcript files (recording1_transcript.txt, etc.)"
echo "2. Edit augment_data.py to specify scam types"
echo "3. Run: python augment_data.py"