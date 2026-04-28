package io.vocaguard.detection

import android.content.Context
import android.util.Log
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamType
import io.vocaguard.ml.CallContext
import io.vocaguard.ml.TFLiteScamClassifier

class HybridScamDetector(private val context: Context) {

    companion object {
        private const val TAG = "HybridScamDetector"
    }

    private val ruleBasedDetector = ScamPatternDetector()
    private val mlClassifier = TFLiteScamClassifier(context)
    private val settings = DetectionSettings.getInstance(context)

    fun analyzeText(text: String, context: CallContext? = null): DetectionResult {
        // Get rule-based detection result
        val ruleBasedResult = ruleBasedDetector.analyzeText(text)

        // Get ML-based detection result (if model is available)
        val mlResult = mlClassifier.classify(text, context)

        // If ML model is not available, use rule-based only
        if (mlResult == null) {
            Log.d(TAG, "ML model unavailable, using rule-based detection only")
            return ruleBasedResult
        }

        // Ensemble: Combine both approaches
        return ensembleResults(ruleBasedResult, mlResult, text)
    }

    private fun ensembleResults(
        ruleResult: DetectionResult,
        mlResult: io.vocaguard.ml.MLDetectionResult,
        text: String
    ): DetectionResult {
        // Weighted ensemble: 40% rule-based, 60% ML
        val ruleWeight = 0.4f
        val mlWeight = 0.6f

        val combinedConfidence = (ruleResult.confidence * ruleWeight) + (mlResult.confidence * mlWeight)

        // Determine if it's a scam — threshold comes from user-configurable sensitivity
        val isScam = combinedConfidence >= settings.confidenceThreshold

        // Choose scam type
        val scamType = when {
            // If both agree on the type, use that
            ruleResult.scamType == mlResult.scamType && isScam -> ruleResult.scamType

            // If ML has high confidence, prefer ML
            mlResult.confidence > 0.8f && mlResult.isScam -> mlResult.scamType

            // If rule-based has high confidence, prefer rule-based
            ruleResult.confidence > 0.8f && ruleResult.isScam -> ruleResult.scamType

            // Use the one with higher confidence
            mlResult.confidence > ruleResult.confidence -> mlResult.scamType

            else -> ruleResult.scamType
        }

        val reason = buildEnsembleReason(ruleResult, mlResult, combinedConfidence)

        Log.d(TAG, "Ensemble result: isScam=$isScam, type=$scamType, confidence=$combinedConfidence")
        Log.d(TAG, "  Rule-based: ${ruleResult.confidence}, ML: ${mlResult.confidence}")

        return DetectionResult(
            isScam = isScam,
            scamType = scamType,
            confidence = combinedConfidence,
            reason = reason,
            keywords = ruleResult.keywords
        )
    }

    private fun buildEnsembleReason(
        ruleResult: DetectionResult,
        mlResult: io.vocaguard.ml.MLDetectionResult,
        combinedConfidence: Float
    ): String {
        val parts = mutableListOf<String>()

        parts.add("Ensemble detection (${(combinedConfidence * 100).toInt()}% confidence)")

        if (ruleResult.isScam) {
            parts.add("Rule-based: ${ruleResult.reason}")
        }

        if (mlResult.isScam) {
            parts.add("ML: ${(mlResult.confidence * 100).toInt()}% ${mlResult.scamType}")
        }

        if (ruleResult.scamType == mlResult.scamType && ruleResult.isScam) {
            parts.add("Both detectors agree on ${ruleResult.scamType}")
        }

        return parts.joinToString("; ")
    }

    fun getDetectorInfo(): String {
        val mlInfo = if (mlClassifier.isModelAvailable()) {
            "ML enabled (${mlClassifier.getModelInfo()})"
        } else {
            "ML disabled (model not found)"
        }
        return "Hybrid Detector: Rule-based + $mlInfo"
    }

    fun isMLEnabled(): Boolean {
        return mlClassifier.isModelAvailable()
    }

    fun release() {
        mlClassifier.release()
    }
}
