# ML Model Training for VocaGuard

This directory contains tools to train a custom TensorFlow Lite model for scam detection.

## Quick Start

### 1. Install Dependencies

```bash
pip install -r requirements.txt
```

### 2. Prepare Training Data

Create a CSV file named `training_data.csv` with two columns:
- `text`: The call transcript text
- `label`: The scam type (0-9)

**Label Mapping:**
- `0` - Legitimate call
- `1` - IRS scam
- `2` - Tech support scam
- `3` - Bank fraud
- `4` - Lottery prize scam
- `5` - Social Security scam
- `6` - Robocall
- `7` - Phishing
- `8` - Insurance scam
- `9` - Donation fraud

**Example CSV:**
```csv
text,label
"This is the IRS. You owe taxes.",1
"Your computer has a virus.",2
"Hello, how are you?",0
```

A sample dataset is provided in `sample_training_data.csv`.

### 3. Train the Model

```bash
python train_model.py
```

This will:
1. Load your training data
2. Extract features
3. Train a neural network
4. Convert to TensorFlow Lite
5. Save as `scam_detector.tflite`

### 4. Deploy the Model

Copy the trained model to the Android app:
```bash
cp scam_detector.tflite ../app/src/main/assets/
```

### 5. Build and Test

Rebuild the app in Android Studio and test with real calls.

## Data Collection Tips

### Getting Training Data

1. **Public Datasets:**
   - FTC scam reports
   - Reddit r/scams transcripts
   - Scam call YouTube videos (with transcription)

2. **User Reports:**
   - Collect transcripts from users who received scams
   - Ask users to report false positives/negatives

3. **Synthetic Data:**
   - Generate variations of known scam scripts
   - Use templates with different phone numbers, names, etc.

### Data Quality

For best results:
- **Balance classes**: Equal examples per scam type (~500+ each)
- **Real transcripts**: Use actual call transcripts, not written text
- **Include errors**: Real transcripts have speech recognition errors
- **Variety**: Different variations of same scam type
- **Legitimate calls**: Include diverse legitimate calls to reduce false positives

## Model Architecture

The model is a simple feedforward neural network:
- Input: 10 numerical features
- Hidden layer 1: 64 neurons + ReLU + Dropout
- Hidden layer 2: 32 neurons + ReLU + Dropout
- Output: 10 classes (softmax)

This architecture is chosen for:
- Fast inference on mobile devices
- Small model size (<500 KB)
- Good accuracy with limited data

## Customization

### Adjust Model Complexity

Edit `train_model.py`:

```python
def create_model():
    model = keras.Sequential([
        keras.layers.Dense(128, activation='relu', input_shape=(10,)),  # More neurons
        keras.layers.Dropout(0.4),  # More dropout
        keras.layers.Dense(64, activation='relu'),
        keras.layers.Dropout(0.4),
        keras.layers.Dense(32, activation='relu'),
        keras.layers.Dropout(0.3),
        keras.layers.Dense(NUM_CLASSES, activation='softmax')
    ])
    return model
```

### Change Feature Extraction

If you modify features:
1. Update `extract_features()` in `train_model.py`
2. Update `extractFeatures()` in `TextPreprocessor.kt`
3. Keep them synchronized!

### Adjust Training Parameters

```python
EPOCHS = 200  # More epochs for more complex models
BATCH_SIZE = 64  # Larger batches for faster training
VALIDATION_SPLIT = 0.2  # Keep 20% for validation
```

## Evaluation

After training, the script shows:
- Overall test accuracy
- Per-class accuracy
- Model size

**Target metrics:**
- Overall accuracy: >85%
- Per-class accuracy: >75%
- Model size: <500 KB

## Troubleshooting

### Low Accuracy

**Problem:** Model accuracy <70%

**Solutions:**
- Collect more training data (aim for 5000+ examples)
- Balance classes (equal examples per type)
- Add more features to `extract_features()`
- Increase model complexity
- Train for more epochs

### High False Positives

**Problem:** Legitimate calls flagged as scams

**Solutions:**
- Collect more legitimate call examples
- Reduce detection threshold in `HybridScamDetector.kt`
- Add more diverse legitimate examples

### Model Not Loading

**Problem:** "Model file not found" error

**Solutions:**
- Check file location: `app/src/main/assets/scam_detector.tflite`
- Verify file was copied correctly
- Clean and rebuild app
- Check file size is >0 bytes

### Large Model Size

**Problem:** Model >1 MB

**Solutions:**
- Reduce neurons in hidden layers
- Apply quantization (already enabled)
- Remove unnecessary layers

## Advanced: Transfer Learning

For better accuracy, use a pre-trained text embedding model:

```python
import tensorflow_hub as hub

# Use Universal Sentence Encoder
embed = hub.load("https://tfhub.dev/google/universal-sentence-encoder/4")

def extract_features_advanced(text):
    # Get 512-dimensional embedding
    embedding = embed([text]).numpy()[0]
    return embedding

# Update model input shape
model = keras.Sequential([
    keras.layers.Dense(128, activation='relu', input_shape=(512,)),
    # ... rest of model
])
```

Note: This increases model size but improves accuracy significantly.

## Model Versioning

When releasing new model versions:
1. Update `modelVersion` in `TFLiteScamClassifier.kt`
2. Keep old model as backup
3. Test thoroughly before deployment
4. Monitor performance metrics

## Support

For issues or questions:
- Check the main MODEL_README.md in assets folder
- Review TensorFlow Lite documentation
- Test with sample_training_data.csv first