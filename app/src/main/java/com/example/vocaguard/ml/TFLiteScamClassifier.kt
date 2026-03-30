package com.example.vocaguard.ml

import android.content.Context
import android.util.Log
import com.example.vocaguard.data.DetectionSettings
import com.example.vocaguard.data.ScamType
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
        private const val NUM_SCAM_TYPES = 11 // 1 legitimate (index 0) + 10 scam types (indices 1-10)
    }

    private var interpreter: Interpreter? = null
    private val textPreprocessor = TextPreprocessor()
    private val settings = DetectionSettings.getInstance(context)
    private var isModelLoaded = false

    // Scam type labels — must match model training order exactly (11 output classes)
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
        ScamType.DONATION_FRAUD   // 10
    )

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(4) // Use 4 threads for faster inference
                setUseNNAPI(true) // Use Android Neural Networks API if available
            }
            interpreter = Interpreter(modelBuffer, options)
            isModelLoaded = true
            Log.i(TAG, "TensorFlow Lite model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TensorFlow Lite model: ${e.message}", e)
            Log.w(TAG, "ML-based detection will be disabled. Using rule-based detection only.")
            isModelLoaded = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
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

    fun classify(text: String): MLDetectionResult? {
        if (!isModelLoaded || interpreter == null) {
            Log.w(TAG, "Model not loaded, skipping ML classification")
            return null
        }

        try {
            // Preprocess text
            val features = textPreprocessor.extractFeatures(text)

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
            "TensorFlow Lite model loaded (v1.0)"
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