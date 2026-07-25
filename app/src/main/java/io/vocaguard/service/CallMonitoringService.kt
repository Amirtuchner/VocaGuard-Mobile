package io.vocaguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Recognizer
import androidx.core.app.NotificationCompat
import io.vocaguard.MainActivity
import io.vocaguard.R
import io.vocaguard.alert.ScamAlertManager
import io.vocaguard.data.CallDirection
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.service.ServerDetectionManager
import io.vocaguard.data.TranscriptRepository
import io.vocaguard.ui.ScamOverlayManager
import io.vocaguard.detection.HybridScamDetector
import io.vocaguard.ml.CallContext
import io.vocaguard.data.ContactsHelper
import io.vocaguard.ml.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class CallMonitoringService : Service() {

    companion object {
        private const val TAG = "CallMonitoringService"
        const val ACTION_START_MONITORING = "io.vocaguard.START_MONITORING"
        const val ACTION_STOP_MONITORING = "io.vocaguard.STOP_MONITORING"
        const val EXTRA_SKIP_FOREGROUND = "skip_foreground"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_monitoring_channel"
        // VAD (Voice Activity Detection) constants
        /**
         * Raw PCM RMS amplitude below which the mic is considered silent.
         * At 16-bit / 16 kHz, amplitude 1585 ≈ -26 dBFS — typical quiet room floor.
         */
        private const val SILENCE_THRESHOLD_DB = 1585f
        /** How many consecutive below-threshold RMS readings constitute silence. */
        private const val SILENCE_CONSECUTIVE_COUNT = 6

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

    private var audioRecord: AudioRecord? = null
    private var voskRecognizer: Recognizer? = null
    private var audioLoopJob: kotlinx.coroutines.Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var usingSpeechRecognizer = false
    private var speechRecognizerErrorCount = 0
    private val MAX_SPEECH_RECOGNIZER_ERRORS = 5
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private var activeCallDirection: CallDirection = CallDirection.INCOMING

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

    // True when registered but no activation code is available.
    // Enables victim-side pattern analysis using local microphone only.
    private var isVictimSideOnly = false

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
                    val skipForeground = intent.getBooleanExtra(EXTRA_SKIP_FOREGROUND, false)
                    startMonitoring(skipForeground)
                }
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()
            }
            "io.vocaguard.A11Y_TEXT" -> {
                // Text forwarded from AccessibilityService audio capture
                val text = intent.getStringExtra("text") ?: ""
                if (text.isNotEmpty() && isMonitoring) {
                    Log.i(TAG, "A11y text received: $text")
                    processRecognizedText(text)
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring(skipForeground: Boolean = false) {
        Log.i(TAG, "Starting call monitoring (skipForeground=$skipForeground)")

        val hasAudio = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot monitor call, skipping")
            stopSelf()
            return
        }

        isMonitoring = true
        activePhoneNumber = scamDatabaseManager.activeCallPhoneNumber
        activeCallDirection = scamDatabaseManager.activeCallDirection
        isVictimSideOnly = ServerDetectionManager.isRegistered() &&
            ServerDetectionManager.getActivationCode(this).isEmpty()

        // Skip detection for known contacts — scammers are never in the contact list.
        if (activePhoneNumber.isNotBlank() &&
            ContactsHelper.isKnownContact(this, activePhoneNumber)) {
            Log.i(TAG, "Known contact ($activePhoneNumber) — scam detection skipped entirely")
            updateNotification("Call with known contact — monitoring inactive")
            return
        }

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

        // Start foreground service with notification — skip if running inside
        // PhoneMonitorService's FGS which already has microphone type.
        if (!skipForeground) {
            val notification = createNotification("Monitoring call for scam patterns...")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot start foreground: ${e.message}")
                stopSelf()
                return
            }
        }

        // If AccessibilityService is available, let it handle audio capture —
        // Samsung Android 15 blocks AudioRecord from regular FGS during calls,
        // but AccessibilityService runs in a privileged context that may bypass this.
        if (VocaGuardAccessibilityService.instance != null) {
            Log.i(TAG, "AccessibilityService available — skipping FGS AudioRecord (Samsung blocks it)")
            updateNotification("Monitoring call for scam patterns...")
        } else {
            // Fallback: start Vosk offline speech recognition via AudioRecord
            startVoskRecognition()
        }
    }

    private fun startVoskRecognition() {
        // Use Vosk directly — Android SpeechRecognizer interferes with call audio
        // (causes audible clicks and can't capture speech during calls)
        Log.i(TAG, "Starting Vosk speech recognition")
        startVoskFallback()
    }

    /** Starts Android's built-in SpeechRecognizer for continuous recognition. */
    private fun startAndroidSpeechRecognizer() {
        usingSpeechRecognizer = true
        mainHandler.post {
            val sr = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = sr

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "SpeechRecognizer ready")
                    updateNotification("Monitoring call for scam patterns...")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Convert dB to approximate raw amplitude for prosody tracking
                    val amplitude = (Math.pow(10.0, rmsdB.toDouble() / 20.0) * 1000).toFloat()
                    totalRmsReadings++
                    if (amplitude < SILENCE_THRESHOLD_DB) {
                        consecutiveLowRmsCount++
                        silentRmsReadings++
                        if (consecutiveLowRmsCount >= LONG_SILENCE_THRESHOLD) hadLongSilence = true
                    } else {
                        consecutiveLowRmsCount = 0
                        if (rmsReadings.size < MAX_RMS_SAMPLES) rmsReadings.add(amplitude / 1000f)
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val errorName = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                        SpeechRecognizer.ERROR_SERVER -> "SERVER"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                        else -> "UNKNOWN($error)"
                    }
                    Log.w(TAG, "SpeechRecognizer error: $errorName")
                    speechRecognizerErrorCount++
                    // Fall back to Vosk after too many consecutive errors
                    if (speechRecognizerErrorCount >= MAX_SPEECH_RECOGNIZER_ERRORS && isMonitoring) {
                        Log.w(TAG, "SpeechRecognizer failed $speechRecognizerErrorCount times, falling back to Vosk")
                        sr.destroy()
                        speechRecognizer = null
                        usingSpeechRecognizer = false
                        startVoskFallback()
                        return
                    }
                    // Restart listening on recoverable errors
                    if (isMonitoring && error != SpeechRecognizer.ERROR_CLIENT) {
                        restartSpeechRecognizer()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Speech result: $text")
                        speechRecognizerErrorCount = 0
                        processRecognizedText(text)
                    }
                    // Restart listening for continuous recognition
                    if (isMonitoring) {
                        restartSpeechRecognizer()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Speech partial: $text")
                        throttledUpdateNotification("Listening: ${text.take(50)}...")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            startListening(sr)
        }
    }

    private fun startListening(sr: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            // Prefer on-device recognition for privacy and speed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false)
            }
            // Allow online recognition — offline en-US model may not be downloaded
            // putExtra("android.speech.extra.PREFER_OFFLINE", true)
        }
        sr.startListening(intent)
    }

    private fun restartSpeechRecognizer() {
        mainHandler.postDelayed({
            if (isMonitoring) {
                speechRecognizer?.let { startListening(it) }
            }
        }, 300)
    }

    /** Processes text from either SpeechRecognizer or Vosk. */
    private fun processRecognizedText(text: String) {
        transcriptBuilder.append(text).append(" ")
        totalWordsRecognized += text.split(" ").count { it.isNotEmpty() }
        throttledUpdateNotification("Listening: ${text.take(50)}...")
        analyzeForScamPatterns(text)
        if (!scamActionTaken) {
            analyzeVictimPatterns(text, transcriptBuilder.toString())
        }
        consecutiveLowRmsCount = 0
    }

    /** Vosk fallback — used when Android SpeechRecognizer is unavailable. */
    private fun startVoskFallback() {
        audioLoopJob = serviceScope.launch(Dispatchers.IO) {
            val model = VoskModelManager.getModel(this@CallMonitoringService)
            if (model == null) {
                Log.e(TAG, "Vosk model unavailable")
                updateNotification("Downloading language model — scam detection will start shortly...")
                return@launch
            }

            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = minBuf.coerceAtLeast(8192)

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                updateNotification("⚠️ Scam detection limited — mic unavailable during call")
                record.release()
                return@launch
            }

            val recognizer = Recognizer(model, sampleRate.toFloat())
            audioRecord = record
            voskRecognizer = recognizer
            val shortBuf = ShortArray(bufSize / 2)

            record.startRecording()
            Log.i(TAG, "Vosk audio loop started (source=VOICE_COMMUNICATION)")
            updateNotification("Monitoring call for scam patterns...")

            try {
                var loopCount = 0
                while (isActive && isMonitoring) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read <= 0) continue

                    // Log RMS every ~10 seconds to verify audio capture
                    loopCount++
                    if (loopCount % 150 == 0) {
                        var sum = 0L
                        for (i in 0 until read) sum += shortBuf[i].toLong() * shortBuf[i]
                        val rms = Math.sqrt(sum.toDouble() / read).toInt()
                        Log.d(TAG, "Audio RMS=$rms (read=$read samples) loop=$loopCount")
                    }

                    updateRmsFromPcm(shortBuf, read)

                    if (!dtmfDetected && detectDtmf(shortBuf.copyOf(read))) {
                        dtmfDetected = true
                        Log.d(TAG, "DTMF tone detected")
                    }

                    if (recognizer.acceptWaveForm(shortBuf, read)) {
                        val text = JSONObject(recognizer.result).optString("text").trim()
                        if (text.isNotEmpty()) {
                            Log.d(TAG, "Vosk result: $text")
                            processRecognizedText(text)
                        }
                    }
                }

                val finalText = JSONObject(recognizer.finalResult).optString("text").trim()
                if (finalText.isNotEmpty()) {
                    transcriptBuilder.append(finalText).append(" ")
                    analyzeForScamPatterns(finalText)
                }
            } finally {
                // Cleanup must happen here, on the thread that actually owns the native
                // AudioRecord/Recognizer objects. stopMonitoring() used to close them
                // directly from the caller's thread while this loop could still be mid
                // native call (acceptWaveForm/finalResult) — a use-after-free race that
                // crashed the whole process with a native SIGSEGV/SIGABRT in libvosk.
                record.stop()
                record.release()
                recognizer.close()
                audioRecord = null
                voskRecognizer = null
                Log.i(TAG, "Vosk audio loop ended")
            }
        }
    }

    /**
     * Computes RMS amplitude from [read] samples of [buffer] and updates all
     * prosody / VAD accumulators (silence ratio, speaker-switch, noise floor).
     * Uses raw amplitude (0–32768) instead of dB since we have direct PCM access.
     */
    private fun updateRmsFromPcm(buffer: ShortArray, read: Int) {
        var sumSq = 0.0
        for (i in 0 until read) sumSq += buffer[i].toDouble() * buffer[i]
        val rms = kotlin.math.sqrt(sumSq / read).toFloat()

        totalRmsReadings++
        if (rms < SILENCE_THRESHOLD_DB) {
            consecutiveLowRmsCount++
            silentRmsReadings++
            if (consecutiveLowRmsCount >= LONG_SILENCE_THRESHOLD) hadLongSilence = true
            if (rms >= NOISE_FLOOR_MIN_DB * 1000f && noiseFloorReadings.size < MAX_RMS_SAMPLES) {
                noiseFloorReadings.add(rms / 1000f)
            }
            if (inSpeechSegment) {
                inSpeechSegment = false
                if (curSegmentRmsCount >= MIN_SEGMENT_SAMPLES) {
                    val segAvg = curSegmentRmsSum / curSegmentRmsCount
                    if (prevSpeechSegmentAvgRms >= 0 &&
                        kotlin.math.abs(segAvg - prevSpeechSegmentAvgRms) > SPEAKER_SWITCH_DB_THRESHOLD * 1000f) {
                        speakerSwitchCount++
                    }
                    prevSpeechSegmentAvgRms = segAvg
                }
                curSegmentRmsSum = 0f
                curSegmentRmsCount = 0
            }
        } else {
            consecutiveLowRmsCount = 0
            if (rmsReadings.size < MAX_RMS_SAMPLES) rmsReadings.add(rms / 1000f)
            if (!inSpeechSegment) inSpeechSegment = true
            curSegmentRmsSum += rms
            curSegmentRmsCount++
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
        val detectionResult = scamDetector.analyzeText(text, buildCallContext(), allowMlOverride = true)

        if (!detectionResult.isScam) return

        Log.w(TAG, "SCAM PATTERN DETECTED: ${detectionResult.scamType} - ${detectionResult.reason}")
        detectedScamTypes.add(detectionResult.scamType.name)

        if (detectionResult.confidence > lastDetectedConfidence) {
            lastDetectedScamType = detectionResult.scamType
            lastDetectedConfidence = detectionResult.confidence
        }

        // Alert once per call — guard with scamActionTaken to prevent a loop when
        // Vosk produces multiple positive segments from the same call.
        if (!scamActionTaken) {
            scamActionTaken = true
            alertManager.triggerScamAlert(
                scamType = detectionResult.scamType,
                transcript = text,
                confidence = detectionResult.confidence,
                phoneNumber = activePhoneNumber
            )
            overlayManager.show(detectionResult.scamType, detectionResult.confidence)
            updateNotification("⚠️ SCAM ALERT: ${detectionResult.scamType}")
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
     * Analyses the victim's side of the call for scam indicators.
     * Called only when [isVictimSideOnly] is true (mic-only, no call forwarding).
     * Uses the accumulated [transcript] so patterns that span multiple Vosk chunks
     * are still detectable.
     */
    private fun analyzeVictimPatterns(chunk: String, transcript: String) {
        val result = scamDetector.analyzeVictimSpeech(chunk, transcript)
        if (!result.isScam) return

        Log.w(TAG, "VICTIM-SIDE SCAM DETECTED: ${result.scamType} — ${result.reason}")
        detectedScamTypes.add(result.scamType.name)

        if (result.confidence > lastDetectedConfidence) {
            lastDetectedScamType  = result.scamType
            lastDetectedConfidence = result.confidence
        }

        if (!scamActionTaken) {
            scamActionTaken = true
            alertManager.triggerScamAlert(
                scamType    = result.scamType,
                transcript  = transcript,
                confidence  = result.confidence,
                phoneNumber = activePhoneNumber
            )
            overlayManager.show(result.scamType, result.confidence)
            updateNotification("⚠️ SCAM ALERT: ${result.scamType}")
            val scamType   = result.scamType
            val confidence = result.confidence
            serviceScope.launch {
                delay(2_000L)
                endScamCall()
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

        // Clean up SpeechRecognizer (must be on main thread)
        if (usingSpeechRecognizer) {
            mainHandler.post {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            usingSpeechRecognizer = false
        }

        // Don't touch audioRecord/voskRecognizer here — the Vosk audio-loop coroutine
        // owns them and cleans them up itself (see startVoskFallback's finally block).
        // Closing them from this thread while that coroutine could still be mid native
        // call caused a use-after-free crash (SIGSEGV/SIGABRT in libvosk).
        val job = audioLoopJob
        audioLoopJob = null
        job?.cancel()
        if (job != null) {
            // Wait for the loop to append its final Vosk result to transcriptBuilder
            // and finish cleanup before we read/clear it in saveTranscript() below —
            // otherwise the last chunk of the call's transcript races the save and
            // can get silently dropped (or the StringBuilder mutated concurrently).
            runBlocking {
                withTimeoutOrNull(2000) { job.join() }
                    ?: Log.w(TAG, "Timed out waiting for Vosk audio loop to finish")
            }
        }

        // Save transcript for review
        saveTranscript()

        // Reset per-call state for the next call
        scamActionTaken = false
        lastDetectedScamType = null
        lastDetectedConfidence = 0f
    }

    private fun saveTranscript() {
        // Save a record for every monitored call, even if no speech was recognized,
        // so that "calls analyzed" in the home screen stats is always accurate.
        if (callStartTime == 0L) return
        val transcript = transcriptBuilder.toString()
        val scamTypes = detectedScamTypes.toList()
        val number = activePhoneNumber
        val direction = activeCallDirection
        // Clear state immediately so the service can handle the next call right away
        transcriptBuilder.clear()
        detectedScamTypes.clear()
        activePhoneNumber = ""
        callStartTime = 0L
        Log.d(TAG, "Saving transcript (${transcript.length} chars)")
        // Use a detached IO scope so the write completes even if the service is destroyed
        CoroutineScope(Dispatchers.IO).launch {
            transcriptRepository.save(
                CallTranscript(text = transcript, detectedScamTypes = scamTypes, phoneNumber = number, direction = direction)
            )
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
