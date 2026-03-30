#!/usr/bin/env python3
"""
VocaGuard Scam Detector Model Training Script

This script trains a TensorFlow Lite model for scam call detection.
"""

import tensorflow as tf
from tensorflow import keras
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
import re

# Configuration
NUM_CLASSES = 11  # 0: legitimate, 1-10: scam types
EPOCHS = 100
BATCH_SIZE = 32
VALIDATION_SPLIT = 0.2

NUM_FEATURES = 26

def extract_features(text):
    """
    Extract numerical features from text.
    Must match TextPreprocessor.extractFeatures() in Android app.
    25 features total.
    """
    text_lower = text.lower()
    text_len = len(text)

    if text_len == 0:
        return [0.0] * NUM_FEATURES

    def flag(*keywords):
        return 1.0 if any(kw in text_lower for kw in keywords) else 0.0

    features = []

    # Features 1-6: Text statistics
    features.append(min(text_len / 1000.0, 1.0))                                         # 1: normalized length
    features.append(min(len(text.split()) / 100.0, 1.0))                                  # 2: normalized word count
    features.append(sum(c.isdigit() for c in text) / text_len)                            # 3: digit ratio
    features.append(text.count('!') / text_len)                                           # 4: exclamation ratio
    features.append(text.count('?') / text_len)                                           # 5: question ratio
    features.append(sum(c.isupper() for c in text) / text_len)                            # 6: uppercase ratio

    # Features 7-10: Generic scam signals
    features.append(flag('urgent', 'immediately', 'right now', 'at once'))                # 7: urgency
    features.append(flag('suspended', 'locked', 'frozen', 'blocked'))                     # 8: account blocked
    features.append(flag('verify', 'confirm', 'validate'))                                # 9: verification
    features.append(flag('money', 'payment', 'funds', 'cash', 'pay'))                     # 10: money

    # Features 11-19: Category-specific keywords
    features.append(flag('irs', 'internal revenue', 'tax department', 'tax debt',
                         'back taxes', 'unpaid tax'))                                      # 11: IRS
    features.append(flag('arrest', 'warrant', 'jail', 'prison', 'prosecution',
                         'charges', 'law enforcement', 'officer'))                         # 12: legal threat
    features.append(flag('virus', 'malware', 'infected', 'spyware', 'ransomware',
                         'remote access', 'anydesk', 'teamviewer'))                        # 13: tech threat
    features.append(flag('microsoft', 'windows', 'apple', 'computer', 'device',
                         'tech support', 'technical support'))                             # 14: tech brand
    features.append(flag('bank', 'credit card', 'debit card', 'account number',
                         'routing number', 'wire transfer', 'pin'))                        # 15: banking
    features.append(flag('won', 'winner', 'prize', 'lottery', 'sweepstakes',
                         'congratulations', 'reward'))                                     # 16: lottery
    features.append(flag('social security', 'ssn', 'social security number',
                         'ss number', 'federal benefits'))                                 # 17: SSN
    features.append(flag('press one', 'press 1', 'recorded message',
                         'automated', 'warranty', 'extended warranty'))                    # 18: robocall
    features.append(flag('password', 'credentials', 'login', 'username',
                         'click', 'link', 'update your'))                                  # 19: phishing

    # Features 20-25: More category signals
    features.append(flag('insurance', 'medicare', 'medicaid', 'health plan',
                         'health insurance', 'coverage', 'enrollment'))                    # 20: insurance
    features.append(flag('investment', 'trading', 'profit', 'returns',
                         'broker', 'portfolio', 'invest', 'stock', 'crypto'))              # 21: investment
    features.append(flag('gift card', 'bitcoin', 'western union', 'wire',
                         'cryptocurrency', 'prepaid card'))                                # 22: payment method
    features.append(flag('free', 'no cost', 'no charge', 'at no cost',
                         'qualify', 'eligible', 'complimentary'))                          # 23: free offer
    features.append(flag('call back', 'call now', 'call immediately',
                         'call us', 'contact us', 'call this number'))                     # 24: callback pressure
    features.append(flag('final notice', 'last chance', 'act now',
                         'time is running out', 'do not delay', 'do not ignore',
                         'last warning', 'failure to'))                                    # 25: deadline pressure
    features.append(flag('charity', 'donate', 'donation', 'help victims',
                         'disaster relief', 'relief fund', 'humanitarian',
                         'tax deductible', 'nonprofit', 'fundraising'))                    # 26: donation fraud

    return features

def load_data(csv_path):
    """
    Load training data from CSV file.

    CSV format:
    text,label
    "This is the IRS. You owe money.",1
    "Your computer has a virus.",2
    "Hello, this is your bank.",3
    ...

    Labels:
    0 - Legitimate call
    1 - IRS scam
    2 - Tech support scam
    3 - Bank fraud
    4 - Lottery prize scam
    5 - Social Security scam
    6 - Robocall
    7 - Phishing
    8 - Insurance scam
    9 - Investment scam
    10 - Donation fraud
    """
    df = pd.read_csv(csv_path)

    # Extract features
    X = np.array([extract_features(text) for text in df['text']])
    y = np.array(df['label'])

    return X, y

def create_model():
    """Create the neural network model."""
    model = keras.Sequential([
        keras.layers.Dense(64, activation='relu', input_shape=(NUM_FEATURES,)),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(32, activation='relu'),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(NUM_CLASSES, activation='softmax')
    ])

    model.compile(
        optimizer='adam',
        loss='sparse_categorical_crossentropy',
        metrics=['accuracy']
    )

    return model

def train_model(X_train, y_train, X_val, y_val):
    """Train the model."""
    model = create_model()

    # Add callbacks
    callbacks = [
        keras.callbacks.EarlyStopping(
            monitor='val_loss',
            patience=10,
            restore_best_weights=True
        ),
        keras.callbacks.ReduceLROnPlateau(
            monitor='val_loss',
            factor=0.5,
            patience=5
        )
    ]

    # Train
    history = model.fit(
        X_train, y_train,
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        validation_data=(X_val, y_val),
        callbacks=callbacks,
        verbose=1
    )

    return model, history

def convert_to_tflite(model, output_path):
    """Convert Keras model to TensorFlow Lite."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)

    # Optimize model
    converter.optimizations = [tf.lite.Optimize.DEFAULT]

    # Convert
    tflite_model = converter.convert()

    # Save
    with open(output_path, 'wb') as f:
        f.write(tflite_model)

    print(f"Model saved to {output_path}")
    print(f"Model size: {len(tflite_model) / 1024:.2f} KB")

def evaluate_model(model, X_test, y_test):
    """Evaluate model performance."""
    loss, accuracy = model.evaluate(X_test, y_test, verbose=0)
    print(f"\nTest Accuracy: {accuracy * 100:.2f}%")
    print(f"Test Loss: {loss:.4f}")

    # Per-class accuracy
    predictions = model.predict(X_test)
    predicted_classes = np.argmax(predictions, axis=1)

    class_names = [
        "Legitimate", "IRS", "Tech Support", "Bank Fraud",
        "Lottery", "Social Security", "Robocall",
        "Phishing", "Insurance", "Investment Scam", "Donation Fraud"
    ]

    print("\nPer-class accuracy:")
    for i in range(NUM_CLASSES):
        mask = y_test == i
        if mask.sum() > 0:
            class_acc = (predicted_classes[mask] == i).sum() / mask.sum()
            print(f"  {class_names[i]}: {class_acc * 100:.2f}%")

def main():
    """Main training pipeline."""
    print("VocaGuard Scam Detector Training")
    print("=" * 50)

    # Load data
    print("\n1. Loading data...")
    X, y = load_data('training_data.csv')
    print(f"   Loaded {len(X)} examples")
    print(f"   Features shape: {X.shape}")

    # Split data
    print("\n2. Splitting data...")
    X_train, X_temp, y_train, y_temp = train_test_split(
        X, y, test_size=0.3, random_state=42
    )
    X_val, X_test, y_val, y_test = train_test_split(
        X_temp, y_temp, test_size=0.5, random_state=42
    )

    print(f"   Training: {len(X_train)} examples")
    print(f"   Validation: {len(X_val)} examples")
    print(f"   Test: {len(X_test)} examples")

    # Features are already normalized ratios [0, 1] — no scaling needed.
    # The Android TextPreprocessor uses the same raw features, so scaling here
    # would cause a train/inference mismatch.

    # Train model
    print("\n4. Training model...")
    model, history = train_model(X_train, y_train, X_val, y_val)

    # Evaluate
    print("\n5. Evaluating model...")
    evaluate_model(model, X_test, y_test)

    # Convert to TensorFlow Lite
    print("\n6. Converting to TensorFlow Lite...")
    convert_to_tflite(model, 'scam_detector.tflite')

    print("\n✓ Training complete!")
    print("\nNext steps:")
    print("1. Copy scam_detector.tflite to app/src/main/assets/")
    print("2. Rebuild and install the app")
    print("3. Check logs for 'ML model loaded successfully'")

if __name__ == '__main__':
    main()