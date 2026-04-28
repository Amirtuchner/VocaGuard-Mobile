package io.vocaguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import io.vocaguard.MainActivity
import io.vocaguard.R
import io.vocaguard.alert.ScamAlertManager
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.data.TranscriptRepository
import io.vocaguard.ui.ScamOverlayManager
import io.vocaguard.detection.HybridScamDetector
import io.vocaguard.ml.CallContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallMonitoringService : Service() {

    companion object {
        private const val TAG = "CallMonitoringService"
        const val ACTION_START_MONITORING = "io.vocaguard.START_MONITORING"
        const val ACTION_STOP_MONITORING = "io.vocaguard.STOP_MONITORING"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_monitoring_channel"
        private const val MAX_SPEECH_RESTARTS = 10

        // VAD (Voice Activity Detection) constants
        /** RMS dB below which the mic is considered silent. */
        private const val SILENCE_THRESHOLD_DB = 2.0f
        /** How many consecutive below-threshold RMS readings constitute silence. */
        private const val SILENCE_CONSECUTIVE_COUNT = 6
        /** Restart delay used during confirmed silence — avoids battery drain from tight polling. */
        private const val SILENCE_RESTART_DELAY_MS = 2_000L
        /** Fast restart delay when there is active speech. */
        private const val SPEECH_RESTART_DELAY_MS = 100L

        /** Consecutive silence readings that count as a "long silence" for the CallContext. */
        const val LONG_SILENCE_THRESHOLD = 30  // ~30 RMS callbacks ≈ 3 s of silence
        /** Cap on stored RMS readings to bound memory use on long calls. */
        private const val MAX_RMS_SAMPLES = 1000

        // Speaker-switch detection
        /** Minimum speech-segment samples before a segment average is trustworthy. */
        private const val MIN_SEGMENT_SAMPLES = 5
        /** RMS dB difference between consecutive speech segments that signals a speaker change. */
        private const val SPEAKER_SWITCH_DB_THRESHOLD = 3.0f

        // Noise floor
        /** Lower bound for "background noise" readings — below this is electronic noise, not real sound. */
        private const val NOISE_FLOOR_MIN_DB = 0.3f

        // DTMF detection via Goertzel algorithm
        /** Assumed sample rate of the raw audio buffer from onBufferReceived (Hz). */
        private const val DTMF_SAMPLE_RATE = 16_000
        /** A row or column frequency must exceed this multiple of the mean power to count as dominant. */
        private const val DTMF_THRESHOLD_RATIO = 3.0
        private val DTMF_ROW_FREQS = listOf(697f, 770f, 852f, 941f)
        private val DTMF_COL_FREQS = listOf(1209f, 1336f, 1477f, 1633f)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var isMonitoring = false
    private var lastNotificationUpdate = 0L
    private lateinit var scamDetector: HybridScamDetector
    private lateinit var alertManager: ScamAlertManager
    private lateinit var transcriptRepository: TranscriptRepository
    private lateinit var scamDatabaseManager: ScamDatabaseManager
    private lateinit var overlayManager: ScamOverlayManager
    private val transcriptBuilder = StringBuilder()
    private val detectedScamTypes = mutableSetOf<String>()
    private var activePhoneNumber: String = ""
    private var speechRestartCount = 0

    // VAD state — tracks consecutive low-RMS readings to detect silence periods
    @Volatile private var consecutiveLowRmsCount = 0

    // RMS / prosody accumulator — all fields written only from the SpeechRecognizer main-thread callbacks
    private val rmsReadings = mutableListOf<Float>()   // non-silent samples only (capped at MAX_RMS_SAMPLES)
    private var totalRmsReadings = 0
    private var silentRmsReadings = 0
    private var hadLongSilence = false
    private var callStartTime = 0L
    private var callStartHour = 0

    // Speaker-switch detection (written from SpeechRecognizer callbacks only)
    private var inSpeechSegment = false
    private var prevSpeechSegmentAvgRms = -1f   // -1 = no previous segment yet
    private var curSegmentRmsSum = 0f
    private var curSegmentRmsCount = 0
    private var speakerSwitchCount = 0

    // Background noise floor (readings below SILENCE_THRESHOLD_DB but above NOISE_FLOOR_MIN_DB)
    private val noiseFloorReadings = mutableListOf<Float>()

    // Speech rate
    private var totalWordsRecognized = 0

    // DTMF / IVR detection
    private var dtmfDetected = false

    // Family Guard: ensure the scam-action sequence runs only once per call
    @Volatile private var scamActionTaken = false
    @Volatile private var lastDetectedScamType: io.vocaguard.data.ScamType? = null
    @Volatile private var lastDetectedConfidence: Float = 0f

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        scamDetector = HybridScamDetector(this)
        alertManager = ScamAlertManager(this)
        transcriptRepository = TranscriptRepository.getInstance(this)
        scamDatabaseManager = ScamDatabaseManager.getInstance(this)
        overlayManager = ScamOverlayManager(this)
        Log.i(TAG, scamDetector.getDetectorInfo())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                if (!isMonitoring) {
                    startMonitoring()
                }
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        Log.i(TAG, "Starting call monitoring")
        isMonitoring = true
        speechRestartCount = 0
        activePhoneNumber = scamDatabaseManager.activeCallPhoneNumber

        // Reset all per-call accumulators
        rmsReadings.clear()
        totalRmsReadings = 0
        silentRmsReadings = 0
        hadLongSilence = false
        callStartTime = System.currentTimeMillis()
        callStartHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        inSpeechSegment = false
        prevSpeechSegmentAvgRms = -1f
        curSegmentRmsSum = 0f
        curSegmentRmsCount = 0
        speakerSwitchCount = 0
        noiseFloorReadings.clear()
        totalWordsRecognized = 0
        dtmfDetected = false

        // Start foreground service with notification
        val notification = createNotification("Monitoring call for scam patterns...")
        startForeground(NOTIFICATION_ID, notification)

        // Initialize speech recognition
        initializeSpeechRecognition()

        // Start speech recognition — callbacks restart it automatically
        startSpeechRecognition()
    }

    private fun initializeSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available on this device")
            updateNotification("⚠️ Scam detection limited — speech recognition unavailable on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    totalRmsReadings++
                    if (rmsdB < SILENCE_THRESHOLD_DB) {
                        consecutiveLowRmsCount++
                        silentRmsReadings++
                        if (consecutiveLowRmsCount >= LONG_SILENCE_THRESHOLD) hadLongSilence = true

                        // Noise floor: collect quiet-but-non-zero readings as background noise evidence
                        if (rmsdB >= NOISE_FLOOR_MIN_DB && noiseFloorReadings.size < MAX_RMS_SAMPLES) {
                            noiseFloorReadings.add(rmsdB)
                        }

                        // Speaker-switch: close out the current speech segment
                        if (inSpeechSegment) {
                            inSpeechSegment = false
                            if (curSegmentRmsCount >= MIN_SEGMENT_SAMPLES) {
                                val segAvg = curSegmentRmsSum / curSegmentRmsCount
                                if (prevSpeechSegmentAvgRms >= 0 &&
                                    kotlin.math.abs(segAvg - prevSpeechSegmentAvgRms) > SPEAKER_SWITCH_DB_THRESHOLD) {
                                    speakerSwitchCount++
                                }
                                prevSpeechSegmentAvgRms = segAvg
                            }
                            curSegmentRmsSum = 0f
                            curSegmentRmsCount = 0
                        }
                    } else {
                        consecutiveLowRmsCount = 0
                        if (rmsReadings.size < MAX_RMS_SAMPLES) rmsReadings.add(rmsdB)

                        // Speaker-switch: accumulate active speech segment
                        if (!inSpeechSegment) inSpeechSegment = true
                        curSegmentRmsSum += rmsdB
                        curSegmentRmsCount++
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Run Goertzel DTMF detection on raw PCM — only until first detection to save CPU
                    if (buffer != null && buffer.size >= 80 && !dtmfDetected) {
                        val samples = ShortArray(buffer.size / 2) { i ->
                            ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort()
                        }
                        if (detectDtmf(samples)) {
                            dtmfDetected = true
                            Log.d(TAG, "DTMF tone detected")
                        }
                    }
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech")
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "Speech recognition error: $error")
                    if (!isMonitoring) return

                    // Silence-related errors: no speech detected or recognition timed out.
                    // These happen constantly during quiet call segments — treat them as
                    // normal silence rather than actual errors to preserve the restart budget
                    // and avoid rapid battery-draining polling.
                    val isSilenceError = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                         error == SpeechRecognizer.ERROR_NO_MATCH
                    val isCurrentlySilent = consecutiveLowRmsCount >= SILENCE_CONSECUTIVE_COUNT

                    val delayMs: Long
                    if (isSilenceError || isCurrentlySilent) {
                        // Don't burn through the restart budget for silence — just wait 2 s.
                        delayMs = SILENCE_RESTART_DELAY_MS
                        Log.d(TAG, "Silence detected, pausing STT for ${delayMs}ms")
                    } else if (speechRestartCount < MAX_SPEECH_RESTARTS) {
                        delayMs = 500L shl speechRestartCount.coerceAtMost(4) // 500ms…8s
                        speechRestartCount++
                        Log.w(TAG, "STT error restart ${speechRestartCount}/$MAX_SPEECH_RESTARTS")
                    } else {
                        Log.e(TAG, "Speech recognition restart limit reached, detection degraded")
                        updateNotification("⚠️ Scam detection limited — speech recognition unavailable")
                        return
                    }

                    serviceScope.launch {
                        delay(delayMs)
                        if (isActive && isMonitoring) startSpeechRecognition()
                    }
                }

                override fun onResults(results: Bundle?) {
                    speechRestartCount = 0
                    consecutiveLowRmsCount = 0 // reset VAD counter — speech was heard
                    handleSpeechResults(results)
                    // Continue recognition
                    if (isMonitoring) {
                        serviceScope.launch {
                            delay(SPEECH_RESTART_DELAY_MS)
                            if (isActive && isMonitoring) {
                                startSpeechRecognition()
                            }
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    handleSpeechResults(partialResults, isPartial = true)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Custom events
                }
            })
        }
    }

    private fun startSpeechRecognition() {
        try {
            val locale = DetectionSettings.getInstance(this).locale
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
        }
    }

    private fun handleSpeechResults(results: Bundle?, isPartial: Boolean = false) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0]
            Log.d(TAG, "Recognized${if (isPartial) " (partial)" else ""}: $text")

            if (!isPartial) {
                // Only append final results — partials are cumulative prefixes that cause transcript bloat
                transcriptBuilder.append(text).append(" ")
                throttledUpdateNotification("Listening: ${text.take(50)}...")
                totalWordsRecognized += text.split(" ").count { it.isNotEmpty() }
            }

            // Analyze current segment for scam patterns regardless of partial/final
            analyzeForScamPatterns(text)
        }
    }

    private fun throttledUpdateNotification(contentText: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate > 2000) {
            lastNotificationUpdate = now
            updateNotification(contentText)
        }
    }

    private fun buildCallContext(): CallContext {
        val avg = if (rmsReadings.isNotEmpty()) rmsReadings.average().toFloat() else 0f
        val stdDev = if (rmsReadings.size > 1) {
            kotlin.math.sqrt(rmsReadings.map { (it - avg) * (it - avg) }.average()).toFloat()
        } else 0f
        val silenceRatio = if (totalRmsReadings > 0) silentRmsReadings.toFloat() / totalRmsReadings else 0f
        val durationSec = (System.currentTimeMillis() - callStartTime) / 1000
        val noiseFloorDb = if (noiseFloorReadings.isNotEmpty()) noiseFloorReadings.average().toFloat() else 0f
        val callDurMin = durationSec / 60.0
        val speechRateWpm = if (callDurMin > 0.1) (totalWordsRecognized / callDurMin).toFloat() else 0f
        return CallContext(
            callDurationSeconds = durationSec,
            avgRmsDb = avg,
            rmsStdDev = stdDev,
            silenceRatio = silenceRatio,
            hadLongSilence = hadLongSilence,
            callStartHour = callStartHour,
            speakerSwitchCount = speakerSwitchCount,
            noiseFloorDb = noiseFloorDb,
            speechRateWpm = speechRateWpm,
            dtmfDetected = dtmfDetected,
        )
    }

    /**
     * Goertzel algorithm — computes the power of [freq] Hz in [samples].
     * More efficient than FFT when checking a small number of target frequencies.
     * Internal so unit tests can verify the algorithm directly.
     */
    internal fun goertzelPower(samples: ShortArray, freq: Float): Double {
        val n = samples.size
        // Use the exact target frequency rather than the nearest DFT bin so that
        // Goertzel power is maximised when the signal is at exactly that frequency.
        val omega = 2.0 * kotlin.math.PI * freq / DTMF_SAMPLE_RATE
        val coeff = 2.0 * kotlin.math.cos(omega)
        var q1 = 0.0
        var q2 = 0.0
        for (s in samples) {
            val q0 = coeff * q1 - q2 + s
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - q1 * q2 * coeff
    }

    /**
     * Returns true if [samples] contains a valid DTMF tone — i.e., exactly one row
     * frequency and one column frequency both dominate over the mean power of all
     * eight DTMF frequencies by at least [DTMF_THRESHOLD_RATIO]×.
     * Internal so unit tests can verify with synthetic audio.
     */
    internal fun detectDtmf(samples: ShortArray): Boolean {
        val rowPowers = DTMF_ROW_FREQS.map { goertzelPower(samples, it) }
        val colPowers = DTMF_COL_FREQS.map { goertzelPower(samples, it) }
        val meanPower = (rowPowers + colPowers).average()
        if (meanPower == 0.0) return false
        val maxRow = rowPowers.maxOrNull() ?: return false
        val maxCol = colPowers.maxOrNull() ?: return false
        return maxRow > meanPower * DTMF_THRESHOLD_RATIO && maxCol > meanPower * DTMF_THRESHOLD_RATIO
    }

    private fun analyzeForScamPatterns(text: String) {
        val detectionResult = scamDetector.analyzeText(text, buildCallContext())

        if (!detectionResult.isScam) return

        Log.w(TAG, "SCAM PATTERN DETECTED: ${detectionResult.scamType} - ${detectionResult.reason}")
        detectedScamTypes.add(detectionResult.scamType.name)

        if (detectionResult.confidence > lastDetectedConfidence) {
            lastDetectedScamType = detectionResult.scamType
            lastDetectedConfidence = detectionResult.confidence
        }

        // Always alert the senior in real time
        alertManager.triggerScamAlert(
            scamType = detectionResult.scamType,
            transcript = text,
            confidence = detectionResult.confidence,
            phoneNumber = activePhoneNumber
        )
        overlayManager.show(detectionResult.scamType, detectionResult.confidence)
        updateNotification("⚠️ SCAM ALERT: ${detectionResult.scamType}")

        // On first confirmed scam: end the scam call and immediately alert the family.
        // Guard prevents the sequence running again if more scam phrases are detected.
        if (!scamActionTaken) {
            scamActionTaken = true
            val scamType = detectionResult.scamType
            val confidence = detectionResult.confidence
            serviceScope.launch {
                // Give the senior's TTS alert a moment to start playing before the call drops
                delay(2_000L)
                endScamCall()
                // Brief pause so the OS registers the call as ended before we dial out
                delay(1_000L)
                io.vocaguard.alert.FamilyAlertSender(this@CallMonitoringService)
                    .makeCallAlert(scamType, confidence, delayBeforeCallMs = 0L)
            }
        }
    }

    /**
     * Terminates the active phone call via [android.telecom.TelecomManager].
     * Requires [android.Manifest.permission.ANSWER_PHONE_CALLS].
     */
    private fun endScamCall() {
        try {
            val telecomManager = getSystemService(android.telecom.TelecomManager::class.java)
            @Suppress("DEPRECATION")
            telecomManager.endCall()
            Log.i(TAG, "Scam call ended by VocaGuard")
        } catch (e: SecurityException) {
            Log.e(TAG, "ANSWER_PHONE_CALLS permission required to end scam call", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end scam call", e)
        }
    }

    private fun stopMonitoring() {
        Log.i(TAG, "Stopping call monitoring")
        isMonitoring = false

        speechRecognizer?.apply {
            stopListening()
            destroy()
        }
        speechRecognizer = null

        // Save transcript for review
        saveTranscript()

        // Reset per-call state for the next call
        scamActionTaken = false
        lastDetectedScamType = null
        lastDetectedConfidence = 0f
    }

    private fun saveTranscript() {
        if (transcriptBuilder.isNotEmpty()) {
            val transcript = transcriptBuilder.toString()
            val scamTypes = detectedScamTypes.toList()
            val number = activePhoneNumber
            // Clear state immediately so the service can handle the next call right away
            transcriptBuilder.clear()
            detectedScamTypes.clear()
            activePhoneNumber = ""
            Log.d(TAG, "Saving transcript (${transcript.length} chars)")
            // Use a detached IO scope so the write completes even if the service is destroyed
            CoroutineScope(Dispatchers.IO).launch {
                transcriptRepository.save(
                    CallTranscript(text = transcript, detectedScamTypes = scamTypes, phoneNumber = number)
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Call Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors phone calls for scam detection"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VocaGuard Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopMonitoring()
        overlayManager.dismiss()
        alertManager.shutdown()
        scamDetector.release()
        serviceScope.cancel()
    }
}
