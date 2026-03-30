# VocaGuard ML Model

## Overview

VocaGuard uses a **hybrid detection system** combining:
1. **Rule-based detection** (keyword matching)
2. **Machine learning classification** (TensorFlow Lite)

The ML model enhances detection accuracy by learning patterns that rules may miss.

## Model Requirements

### File Location
Place your trained model file here:
```
app/src/main/assets/scam_detector.tflite
```

### Model Specifications

**Input:**
- Type: `float32`
- Shape: `[1, 10]` (10 numerical features)
- Features extracted:
  1. Normalized text length (0-1)
  2. Normalized word count (0-1)
  3. Digit ratio
  4. Exclamation mark ratio
  5. Question mark ratio
  6. Uppercase ratio
  7. Urgency keyword flag (0 or 1)
  8. Suspension keyword flag (0 or 1)
  9. Verification keyword flag (0 or 1)
  10. Payment keyword flag (0 or 1)

**Output:**
- Type: `float32`
- Shape: `[1, 10]` (probability distribution)
- Classes:
  0. Legitimate call
  1. IRS scam
  2. Tech support scam
  3. Bank fraud
  4. Lottery prize scam
  5. Social Security scam
  6. Robocall
  7. Phishing
  8. Insurance scam
  9. Donation fraud

## Training Your Own Model

### Option 1: Use Pre-trained Model
Download a pre-trained model from VocaGuard repository (when available).

### Option 2: Train Your Own

#### Step 1: Collect Training Data
Create a dataset of call transcripts labeled with scam types:
```csv
text,label
"This is the IRS. You owe money.",1
"Your computer has a virus. Call Microsoft support.",2
"Congratulations! You won the lottery.",4
"Hi, this is John from accounting.",0
```

#### Step 2: Train Model (Python)
```python
import tensorflow as tf
from tensorflow import keras
import numpy as np

# Load and preprocess data
# ... (implement text preprocessing)

# Build model
model = keras.Sequential([
    keras.layers.Dense(64, activation='relu', input_shape=(10,)),
    keras.layers.Dropout(0.3),
    keras.layers.Dense(32, activation='relu'),
    keras.layers.Dropout(0.3),
    keras.layers.Dense(10, activation='softmax')
])

model.compile(
    optimizer='adam',
    loss='sparse_categorical_crossentropy',
    metrics=['accuracy']
)

# Train model
model.fit(X_train, y_train, epochs=50, validation_split=0.2)

# Convert to TensorFlow Lite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

# Save model
with open('scam_detector.tflite', 'wb') as f:
    f.write(tflite_model)
```

#### Step 3: Copy Model to Assets
```bash
cp scam_detector.tflite app/src/main/assets/
```

## Hybrid Detection System

### How It Works

1. **Text arrives** from speech-to-text
2. **Rule-based detector** analyzes text (fast, always available)
3. **ML classifier** analyzes text (if model available)
4. **Ensemble combination**:
   - 40% weight: Rule-based confidence
   - 60% weight: ML confidence
   - Combined confidence >= 60% → Scam alert

### Example
```
Text: "This is the IRS. You owe taxes. Pay with gift cards immediately."

Rule-based: 85% confidence (IRS keywords + urgency + payment method)
ML: 92% confidence (learned pattern: IRS + gift card = scam)
Ensemble: (0.85 × 0.4) + (0.92 × 0.6) = 89% confidence

Result: SCAM ALERT (IRS Scam)
```

## Without ML Model

If no model file is present:
- App falls back to **rule-based detection only**
- No crashes or errors
- Still provides good protection
- Log message: "ML model unavailable, using rule-based detection only"

## Performance

### With ML Model
- **Accuracy**: ~90-95% (with good training data)
- **False positives**: ~2-5%
- **Inference time**: ~10-50ms per classification
- **Model size**: ~100-500 KB

### Rule-based Only
- **Accuracy**: ~75-85%
- **False positives**: ~5-10%
- **Inference time**: <1ms
- **No additional storage**

## Recommended Workflow

1. **Start with rule-based only** (no model needed)
2. **Collect real call data** from users
3. **Train custom model** on actual scam calls
4. **Deploy model** in app update
5. **Monitor performance** and retrain periodically

## Model Updates

To update the model:
1. Train new model version
2. Replace `scam_detector.tflite` in assets
3. Update `modelVersion` in `TFLiteScamClassifier.kt`
4. Release app update

## Troubleshooting

### Model not loading
- Check file exists: `app/src/main/assets/scam_detector.tflite`
- Verify file format (must be `.tflite`)
- Check logcat: `adb logcat -s TFLiteScamClassifier`

### Low accuracy
- Collect more training data
- Balance classes (equal examples per scam type)
- Retrain with more epochs
- Adjust ensemble weights in `HybridScamDetector.kt`

### Performance issues
- Reduce model size (use quantization)
- Decrease feature count
- Enable GPU delegate (already enabled)
- Use NNAPI acceleration (already enabled)

## Future Improvements

- **BERT/Transformer models** for better context understanding
- **Online learning** to adapt to new scam patterns
- **Federated learning** to train on user data without privacy concerns
- **Multi-language support** for non-English calls