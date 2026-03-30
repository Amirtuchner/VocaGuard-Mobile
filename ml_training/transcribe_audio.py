#!/usr/bin/env python3
"""
Audio Transcription Tool for VocaGuard

Transcribes phone recordings to text for model training.
"""

import os
import sys

def transcribe_with_google_cloud(audio_file):
    """
    Transcribe audio using Google Cloud Speech-to-Text API.
    Requires: pip install google-cloud-speech
    """
    from google.cloud import speech

    client = speech.SpeechClient()

    with open(audio_file, 'rb') as f:
        audio = speech.RecognitionAudio(content=f.read())

    config = speech.RecognitionConfig(
        encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
        sample_rate_hertz=16000,
        language_code="en-US",
        enable_automatic_punctuation=True,
        enable_word_time_offsets=True,
        enable_speaker_diarization=True,  # Separate speakers
        diarization_speaker_count=2,  # You + scammer
    )

    print(f"Transcribing {audio_file}...")
    response = client.recognize(config=config, audio=audio)

    transcript = ""
    for result in response.results:
        transcript += result.alternatives[0].transcript + " "

    return transcript.strip()

def transcribe_with_whisper(audio_file):
    """
    Transcribe audio using OpenAI Whisper (local, free).
    Requires: pip install openai-whisper
    """
    import whisper

    print(f"Loading Whisper model...")
    model = whisper.load_model("base")  # Options: tiny, base, small, medium, large

    print(f"Transcribing {audio_file}...")
    result = model.transcribe(audio_file, language="en")

    return result["text"]

def transcribe_with_assembly(audio_file):
    """
    Transcribe audio using AssemblyAI (free tier available).
    Requires: pip install assemblyai
    Set environment variable: export ASSEMBLYAI_API_KEY=your_key
    """
    import assemblyai as aai

    aai.settings.api_key = os.environ.get("ASSEMBLYAI_API_KEY")

    transcriber = aai.Transcriber()

    config = aai.TranscriptionConfig(
        speaker_labels=True,  # Identify different speakers
        language_code="en"
    )

    print(f"Uploading and transcribing {audio_file}...")
    transcript = transcriber.transcribe(audio_file, config=config)

    # Get text with speaker labels
    full_text = []
    for utterance in transcript.utterances:
        full_text.append(f"Speaker {utterance.speaker}: {utterance.text}")

    return "\n".join(full_text)

def transcribe_file(audio_file, method):
    if method == "1":
        return transcribe_with_whisper(audio_file)
    elif method == "2":
        return transcribe_with_assembly(audio_file)
    elif method == "3":
        return transcribe_with_google_cloud(audio_file)
    else:
        raise ValueError(f"Invalid method: {method}")


def save_transcript(audio_file, transcript):
    output_file = audio_file.rsplit('.', 1)[0] + '_transcript.txt'
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(transcript)
    return output_file


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Transcribe audio files for VocaGuard training")
    parser.add_argument('files', nargs='*', help="Audio file(s) to transcribe")
    parser.add_argument('--batch', action='store_true', help="Transcribe all recording*.mp3/mp4 in current directory")
    parser.add_argument('--method', choices=['1', '2', '3'], default=None,
                        help="1=Whisper (default), 2=AssemblyAI, 3=Google Cloud")
    args = parser.parse_args()

    # Resolve files
    if args.batch:
        import glob
        files = sorted(glob.glob('recording*.mp3') + glob.glob('recording*.mp4'))
        if not files:
            print("No recording*.mp3 or recording*.mp4 files found in current directory")
            sys.exit(1)
        print(f"Batch mode: found {len(files)} file(s): {files}")
    elif args.files:
        files = args.files
    else:
        parser.print_help()
        print("\nExamples:")
        print("  python transcribe_audio.py recording1.mp3")
        print("  python transcribe_audio.py --batch")
        print("  python transcribe_audio.py --batch --method 1")
        sys.exit(1)

    # Resolve method
    method = args.method
    if method is None:
        print("Choose transcription method:")
        print("  1. OpenAI Whisper (recommended, free, local)")
        print("  2. AssemblyAI (free tier, online)")
        print("  3. Google Cloud Speech-to-Text (paid)")
        method = input("Enter choice (1-3): ").strip()

    # Transcribe
    errors = []
    for audio_file in files:
        if not os.path.exists(audio_file):
            print(f"⚠ Skipping (not found): {audio_file}")
            errors.append(audio_file)
            continue

        output_file = audio_file.rsplit('.', 1)[0] + '_transcript.txt'
        if os.path.exists(output_file):
            print(f"⚠ Skipping (transcript exists): {output_file}")
            continue

        try:
            transcript = transcribe_file(audio_file, method)
            saved = save_transcript(audio_file, transcript)
            print(f"✓ Saved: {saved}")
            print(f"  Preview: {transcript[:200]}...\n")
        except Exception as e:
            print(f"FAILED: Error transcribing {audio_file}: {e}")
            errors.append(audio_file)

    if errors:
        print(f"\n{len(errors)} file(s) failed: {errors}")
        print("Make sure the required library is installed:")
        print("  pip install openai-whisper  (for Whisper)")
        print("  pip install assemblyai      (for AssemblyAI)")
        print("  pip install google-cloud-speech  (for Google Cloud)")
    else:
        print("\n✓ All done!")
        print("\nNext steps:")
        print("1. Review each *_transcript.txt and note the scam type in each")
        print("2. Update the labels in augment_data.py:")
        print("   0=Legitimate  1=IRS  2=Tech Support  3=Bank Fraud")
        print("   4=Lottery     5=SSN  6=Robocall       7=Phishing")
        print("   8=Insurance   9=Investment Scam")
        print("3. Run: python augment_data.py")
        print("4. Run: python train_model.py")


if __name__ == '__main__':
    main()