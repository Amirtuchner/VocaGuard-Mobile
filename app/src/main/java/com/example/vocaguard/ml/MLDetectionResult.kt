package com.example.vocaguard.ml

import com.example.vocaguard.data.ScamType

data class MLDetectionResult(
    val isScam: Boolean,
    val confidence: Float, // 0.0 to 1.0
    val scamType: ScamType,
    val scamProbabilities: Map<ScamType, Float>, // Probability for each scam type
    val modelVersion: String
)