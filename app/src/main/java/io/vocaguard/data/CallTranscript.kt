package io.vocaguard.data

import java.util.concurrent.atomic.AtomicLong

private val idCounter = AtomicLong(System.currentTimeMillis())

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class CallTranscript(
    val id: Long = idCounter.incrementAndGet(),
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val detectedScamTypes: List<String> = emptyList(),
    val phoneNumber: String = "",
    val isFalsePositive: Boolean = false,
    val direction: CallDirection = CallDirection.INCOMING
)
