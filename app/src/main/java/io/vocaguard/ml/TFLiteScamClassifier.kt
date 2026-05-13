package io.vocaguard.ml

import android.content.Context
import android.util.Log
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteScamClassifier(private val context: Context) {

    companion object {
        private const val TAG = "TFLiteScamClassifier"
        private const val MODEL_FILENAME = "scam_detector.tflite"
        private const val NUM_SCAM_TYPES = 14 // 1 legitimate (index 0) + 13 scam types (indices 1-13)
    }

    private var interpreter: Interpreter? = null
    private val textPreprocessor = TextPreprocessor()
    private val settings = DetectionSettings.getInstance(context)
    private var isModelLoaded = false

    // Scam type labels — must match model training order exactly (14 output classes)
    private val scamTypeLabels = arrayOf(
        ScamType.UNKNOWN,         // 0: Legitimate call
        ScamType.IRS_SCAM,        // 1
        ScamType.TECH_SUPPORT,    // 2
        ScamType.BANK_FRAUD,      // 3
        ScamType.LOTTERY_PRIZE,   // 4
        ScamType.SOCIAL_SECURITY, // 5
        ScamType.ROBOCALL,        // 6
        ScamType.PHISHING,        // 7
        ScamType.INSURANCE,       // 8
        ScamType.INVESTMENT_SCAM, // 9
        ScamType.DONATION_FRAUD,  // 10
        ScamType.ROMANCE_SCAM,    // 11
        ScamType.DELIVERY_SCAM,   // 12
        ScamType.JOB_SCAM         // 13
    )

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            val interp = Interpreter(modelBuffer, options)

            // Validate that the model's input size matches the current feature vector.
            // A mismatch means the bundled .tflite was not rebuilt after a feature change.
            val inputShape = interp.getInputTensor(0).shape() // [batch, features]
            val modelFeatureCount = if (inputShape.size >= 2) inputShape[1] else -1
            val expectedFeatureCount = TextPreprocessor.EXPECTED_FEATURE_COUNT
            if (modelFeatureCount != expectedFeatureCount) {
                Log.e(TAG, "FEATURE MISMATCH: model expects $modelFeatureCount features " +
                    "but TextPreprocessor produces $expectedFeatureCount. " +
                    "Rebuild scam_detector.tflite with train_model.py and redeploy to assets/.")
                interp.close()
                isModelLoaded = false
                return
            }

            interpreter = interp
            isModelLoaded = true
            Log.i(TAG, "TensorFlow Lite model loaded successfully " +
                "(features=$modelFeatureCount, version=FEATURE_VERSION ${TextPreprocessor.FEATURE_VERSION})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TensorFlow Lite model: ${e.message}", e)
            Log.w(TAG, "ML-based detection will be disabled. Using rule-based detection only.")
            isModelLoaded = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        // Prefer an OTA-downloaded model in filesDir; fall back to the bundled asset.
        val downloadedModel = ModelUpdateManager.getInstance(context).modelFile
        if (downloadedModel.exists() && downloadedModel.length() > 0) {
            Log.i(TAG, "Loading updated model from ${downloadedModel.absolutePath}")
            return FileInputStream(downloadedModel).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
            }
        }

        return try {
            val fd = context.assets.openFd(MODEL_FILENAME)
            // FileInputStream is closed after mapping; MappedByteBuffer remains valid
            // because it is backed by an OS-level memory mapping, not the open channel.
            FileInputStream(fd.fileDescriptor).use { fis ->
                fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model file not found: $MODEL_FILENAME", e)
            throw Exception("Model file not found. Please add $MODEL_FILENAME to assets folder.")
        }
    }

    fun classify(text: String, context: CallContext? = null): MLDetectionResult? {
        if (!isModelLoaded || interpreter == null) {
            Log.w(TAG, "Model not loaded, skipping ML classification")
            return null
        }

        try {
            // Preprocess text
            val features = textPreprocessor.extractFeatures(text, context)

            // Prepare input buffer
            val inputBuffer = ByteBuffer.allocateDirect(features.size * 4).apply {
                order(ByteOrder.nativeOrder())
                features.forEach { putFloat(it) }
                rewind() // reset position to 0 so the interpreter reads from the start
            }

            // Prepare output buffer (probabilities for each scam type)
            val outputBuffer = ByteBuffer.allocateDirect(NUM_SCAM_TYPES * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Parse output
            outputBuffer.rewind()
            val probabilities = FloatArray(NUM_SCAM_TYPES)
            outputBuffer.asFloatBuffer().get(probabilities)

            // Find the class with highest probability
            val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
            val maxConfidence = probabilities[maxIndex]

            // Create probability map
            val scamProbabilities = mutableMapOf<ScamType, Float>()
            for (i in probabilities.indices) {
                if (i < scamTypeLabels.size) {
                    scamProbabilities[scamTypeLabels[i]] = probabilities[i]
                }
            }

            // Determine if it's a scam — uses the user-configurable sensitivity threshold
            val isScam = maxIndex > 0 && maxConfidence >= settings.confidenceThreshold
            val scamType = if (isScam) scamTypeLabels[maxIndex] else ScamType.UNKNOWN

            Log.d(TAG, "ML Classification: isScam=$isScam, type=$scamType, confidence=$maxConfidence")

            return MLDetectionResult(
                isScam = isScam,
                confidence = maxConfidence,
                scamType = scamType,
                scamProbabilities = scamProbabilities,
                modelVersion = "1.0"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during classification", e)
            return null
        }
    }

    fun classifyBatch(texts: List<String>): List<MLDetectionResult?> {
        return texts.map { classify(it) }
    }

    fun isModelAvailable(): Boolean {
        return isModelLoaded && interpreter != null
    }

    fun getModelInfo(): String {
        return if (isModelLoaded) {
            val version = ModelUpdateManager.getInstance(context).currentVersion
            "TensorFlow Lite model loaded (v$version)"
        } else {
            "Model not available - using rule-based detection only"
        }
    }

    fun release() {
        try {
            interpreter?.close()
            interpreter = null
            isModelLoaded = false
            Log.d(TAG, "TensorFlow Lite interpreter released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing interpreter", e)
        }
    }
}
