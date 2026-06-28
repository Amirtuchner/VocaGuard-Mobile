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

    fun analyzeText(text: String, context: CallContext? = null, allowMlOverride: Boolean = false): DetectionResult {
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
        return ensembleResults(ruleBasedResult, mlResult, hasCallContext = context != null, allowMlOverride = allowMlOverride)
    }

    private fun ensembleResults(
        ruleResult: DetectionResult,
        mlResult: io.vocaguard.ml.MLDetectionResult,
        hasCallContext: Boolean,
        allowMlOverride: Boolean = false
    ): DetectionResult {
        // When a CallContext is available (live calls), the ML model has 11 audio/prosody
        // features to work with in addition to text features — trust it more.
        // When there is no context (message scanning, text-only), those 11 features are
        // always 0, so the ML has less signal; give the rule-based detector more weight.
        val ruleWeight = if (hasCallContext) 0.4f else 0.6f
        val mlWeight   = if (hasCallContext) 0.6f else 0.4f

        val combinedConfidence = (ruleResult.confidence * ruleWeight) + (mlResult.confidence * mlWeight)

        // Determine if it's a scam — threshold comes from user-configurable sensitivity.
        // allowMlOverride: for live calls where STT noise can zero out the rule-based score,
        // trust ML alone if it is ≥90% confident. Not used for message scanning.
        Log.d(TAG, "  allowMlOverride=$allowMlOverride, threshold=${settings.confidenceThreshold}, combined=$combinedConfidence")
        val isScam = combinedConfidence >= settings.confidenceThreshold ||
                     (allowMlOverride && mlResult.confidence >= 0.80f)

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

    /**
     * Analyses a text message (WhatsApp / Telegram / Messenger).
     *
     * Uses [ScamPatternDetector.analyzeMessage] which only fires on high-specificity,
     * multi-word phrases — making false positives from casual chat near-impossible.
     * The ML classifier is used as a secondary guard only when the rule engine does
     * not fire, to catch novel scam language not yet in the phrase list.
     */
    fun analyzeMessage(text: String): DetectionResult {
        // Messages use rule-based phrase matching ONLY.
        // The TFLite model is trained on phone-call transcripts and produces near-100%
        // false-positive rates on normal chat — it is intentionally excluded here.
        val ruleResult = ruleBasedDetector.analyzeMessage(text)
        Log.d(TAG, "Message rule result: isScam=${ruleResult.isScam}, confidence=${ruleResult.confidence}, type=${ruleResult.scamType}")
        return ruleResult
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
