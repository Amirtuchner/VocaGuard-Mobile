package com.example.vocaguard.detection

import com.example.vocaguard.data.ScamType

data class DetectionResult(
    val isScam: Boolean,
    val scamType: ScamType,
    val confidence: Float, // 0.0 to 1.0
    val reason: String,
    val keywords: List<String> = emptyList()
)