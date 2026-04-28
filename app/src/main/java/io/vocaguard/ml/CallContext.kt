package io.vocaguard.ml

/**
 * Audio, prosody, and call-metadata signals captured during a live call.
 * Passed alongside the transcript into [TextPreprocessor.extractFeatures] so
 * the ML model can use non-textual evidence to detect novel-language scams.
 *
 * All float fields should be in their raw domain units; [TextPreprocessor]
 * normalises them to [0, 1] before adding them to the feature vector.
 */
data class CallContext(
    /** Seconds elapsed since the call was answered. */
    val callDurationSeconds: Long,

    /** Mean RMS level (dB) of non-silent audio frames observed so far. */
    val avgRmsDb: Float,

    /**
     * Standard deviation of non-silent RMS readings.
     * Low value → monotone / synthesised voice; high value → natural speech.
     */
    val rmsStdDev: Float,

    /** Fraction of RMS readings that were below the silence threshold [0, 1]. */
    val silenceRatio: Float,

    /** True if any silence gap exceeded [CallMonitoringService.LONG_SILENCE_THRESHOLD] consecutive readings. */
    val hadLongSilence: Boolean,

    /** Hour of day the call started (0–23). Used to flag off-hours calls. */
    val callStartHour: Int,

    /**
     * Number of times the per-segment RMS average shifted by more than 3 dB between
     * consecutive speech segments — a proxy for speaker handoffs.
     */
    val speakerSwitchCount: Int,

    /**
     * Average RMS level (dB) during quiet periods (below silence threshold but above
     * electronic noise floor). A non-zero value indicates persistent background noise
     * such as call-centre chatter or hold music.
     */
    val noiseFloorDb: Float,

    /**
     * Estimated words per minute derived from transcript word count and call duration.
     * Very high rates (>200 wpm) are typical of robocalls or TTS; near-zero means
     * the call was mostly silent or very short.
     */
    val speechRateWpm: Float,

    /**
     * True if Goertzel analysis of a raw audio buffer detected a valid DTMF row+column
     * frequency pair — indicates IVR menus or call-routing tones.
     */
    val dtmfDetected: Boolean,
)
