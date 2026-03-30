package com.example.vocaguard.service

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
import com.example.vocaguard.MainActivity
import com.example.vocaguard.R
import com.example.vocaguard.alert.ScamAlertManager
import com.example.vocaguard.data.CallTranscript
import com.example.vocaguard.data.DetectionSettings
import com.example.vocaguard.data.ScamDatabaseManager
import com.example.vocaguard.data.TranscriptRepository
import com.example.vocaguard.ui.ScamOverlayManager
import com.example.vocaguard.detection.HybridScamDetector
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
        const val ACTION_START_MONITORING = "com.example.vocaguard.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.vocaguard.STOP_MONITORING"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_monitoring_channel"
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
        activePhoneNumber = scamDatabaseManager.activeCallPhoneNumber

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
                    // Audio level changed
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Partial audio buffer
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech")
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "Speech recognition error: $error")
                    // Restart recognition if still monitoring
                    if (isMonitoring) {
                        serviceScope.launch {
                            delay(500) // Brief delay before restarting
                            if (isActive && isMonitoring) {
                                startSpeechRecognition()
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    handleSpeechResults(results)
                    // Continue recognition
                    if (isMonitoring) {
                        serviceScope.launch {
                            delay(100)
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

    private fun analyzeForScamPatterns(text: String) {
        val detectionResult = scamDetector.analyzeText(text)

        if (detectionResult.isScam) {
            Log.w(TAG, "SCAM PATTERN DETECTED: ${detectionResult.scamType} - ${detectionResult.reason}")
            detectedScamTypes.add(detectionResult.scamType.name)

            alertManager.triggerScamAlert(
                scamType = detectionResult.scamType,
                transcript = text,
                confidence = detectionResult.confidence,
                phoneNumber = activePhoneNumber
            )
            overlayManager.show(detectionResult.scamType, detectionResult.confidence)
            updateNotification("⚠️ SCAM ALERT: ${detectionResult.scamType}")
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