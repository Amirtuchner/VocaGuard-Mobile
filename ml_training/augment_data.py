#!/usr/bin/env python3
"""
Data Augmentation Tool

Expands your 3 real scam call transcripts into many training examples.
"""

import re
import random

def extract_scam_phrases(transcript):
    """
    Extract individual scam phrases from a full conversation.

    Input: Full conversation transcript
    Output: List of scam phrases (3-5 sentences each)
    """
    # Split by sentences
    sentences = re.split(r'[.!?]+', transcript)
    sentences = [s.strip() for s in sentences if len(s.strip()) > 10]

    phrases = []

    # Create overlapping windows of 3-5 sentences
    for i in range(len(sentences)):
        for window_size in [3, 4, 5]:
            if i + window_size <= len(sentences):
                phrase = ' '.join(sentences[i:i+window_size])
                if len(phrase) > 50:  # Minimum length
                    phrases.append(phrase)

    return phrases

def generate_variations(text):
    """
    Create variations of the same scam text.

    This helps the model learn patterns, not exact phrases.
    """
    variations = [text]

    # Variation 1: Change numbers
    var1 = re.sub(r'\d+', lambda m: str(int(m.group()) + random.randint(-100, 100)), text)
    variations.append(var1)

    # Variation 2: Synonym replacement
    synonyms = {
        'immediately': ['right now', 'urgently', 'at once'],
        'call': ['phone', 'contact', 'reach out'],
        'money': ['payment', 'funds', 'cash'],
        'verify': ['confirm', 'validate', 'check'],
        'suspended': ['locked', 'frozen', 'blocked'],
    }

    var2 = text
    for word, syns in synonyms.items():
        if word in text.lower():
            var2 = re.sub(word, random.choice(syns), var2, flags=re.IGNORECASE)
    variations.append(var2)

    # Variation 3: Add/remove filler words
    fillers = ['um', 'uh', 'you know', 'like', 'so']
    words = text.split()
    for i in range(0, len(words), 3):
        words.insert(i, random.choice(fillers))
    var3 = ' '.join(words)
    variations.append(var3)

    return variations

def process_recording(transcript_file, scam_type_label):
    """
    Process one recording transcript into many training examples.

    Args:
        transcript_file: Path to transcript .txt file
        scam_type_label: 1-9 (scam type, where 9 = Investment Scam)

    Returns:
        List of (text, label) tuples
    """
    with open(transcript_file, 'r', encoding='utf-8') as f:
        transcript = f.read()

    # Extract phrases
    phrases = extract_scam_phrases(transcript)
    print(f"  Extracted {len(phrases)} phrases from transcript")

    # Generate variations
    training_examples = []
    for phrase in phrases:
        variations = generate_variations(phrase)
        for var in variations:
            training_examples.append((var, scam_type_label))

    print(f"  Generated {len(training_examples)} training examples")
    return training_examples

def main():
    """
    Example usage:

    1. Transcribe your 3 recordings to .txt files
    2. Update the file paths below
    3. Run this script to generate training data
    """

    print("VocaGuard Data Augmentation Tool")
    print("=" * 50)

    # Configure your recordings here
    # 0 = Legitimate, 1 = IRS, 2 = Tech Support, 3 = Bank Fraud
    # 4 = Lottery, 5 = Social Security, 6 = Robocall
    # 7 = Phishing, 8 = Insurance, 9 = Investment Scam
    recordings = [
        # (transcript_file, scam_type_label)
        ('recording1_transcript.txt', 9),  # Investment scam
        ('recording2_transcript.txt', 9),  # Investment scam
        ('recording3_transcript.txt', 9),  # Investment scam
        ('recording4_transcript.txt', 9),  # Investment scam
        ('recording5_transcript.txt', 9),  # Investment scam
        ('recording6_transcript.txt', 9),  # Investment scam
        ('recording7_transcript.txt', 9),  # Investment scam
        ('recording8_transcript.txt', 9),  # Investment scam
        ('recording9_transcript.txt', 9),  # Investment scam
    ]

    all_examples = []

    for transcript_file, label in recordings:
        try:
            print(f"\nProcessing {transcript_file}...")
            examples = process_recording(transcript_file, label)
            all_examples.extend(examples)
        except FileNotFoundError:
            print(f"  WARNING: File not found: {transcript_file}")
            print(f"  Create it by transcribing your audio recording")
            continue

    if not all_examples:
        print("\n❌ No training examples generated.")
        print("\nNext steps:")
        print("1. Transcribe your audio recordings to .txt files")
        print("2. Update the 'recordings' list in this script")
        print("3. Run again")
        return

    # Load existing sample data
    print(f"\nOK Generated {len(all_examples)} examples from your recordings")
    print("  Combining with sample_training_data.csv...")

    import pandas as pd

    # Load sample data
    try:
        sample_df = pd.read_csv('sample_training_data.csv')
        print(f"  Loaded {len(sample_df)} sample examples")
    except (FileNotFoundError, pd.errors.ParserError):
        print("  WARNING: sample_training_data.csv not found, using your data only")
        sample_df = pd.DataFrame(columns=['text', 'label'])

    # Combine
    your_df = pd.DataFrame(all_examples, columns=['text', 'label'])
    combined_df = pd.concat([sample_df, your_df], ignore_index=True)

    # Shuffle
    combined_df = combined_df.sample(frac=1, random_state=42).reset_index(drop=True)

    # Save
    output_file = 'training_data.csv'
    combined_df.to_csv(output_file, index=False)

    print(f"\nOK Saved {len(combined_df)} total examples to {output_file}")
    print(f"\n  Breakdown:")
    print(f"    From your recordings: {len(all_examples)}")
    print(f"    From sample data: {len(sample_df)}")
    print(f"    Total: {len(combined_df)}")

    print("\nOK Ready to train!")
    print("  Run: python train_model.py")

if __name__ == '__main__':
    main()