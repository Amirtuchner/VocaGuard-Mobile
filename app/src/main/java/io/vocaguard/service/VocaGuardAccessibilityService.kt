package io.vocaguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.vocaguard.data.DetectionSettings
import io.vocaguard.ml.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * AccessibilityService that captures call audio for scam detection.
 *
 * Samsung Android 15 blocks AudioRecord from regular foreground services during
 * phone calls. AccessibilityService runs in a privileged context that may bypass
 * this restriction. When a call is detected (via PhoneMonitorService broadcast),
 * this service starts an AudioRecord loop with Vosk speech recognition and
 * forwards recognized text to CallMonitoringService for scam pattern analysis.
 */
class VocaGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VocaGuardA11y"
        const val ACTION_START_AUDIO = "io.vocaguard.a11y.START_AUDIO"
        const val ACTION_STOP_AUDIO = "io.vocaguard.a11y.STOP_AUDIO"

        @Volatile
        var instance: VocaGuardAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var audioLoopJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var voskRecognizer: Recognizer? = null
    @Volatile
    private var isCapturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events for audio capture.
        // This service is primarily used for its privileged process context.
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUDIO -> startAudioCapture()
            ACTION_STOP_AUDIO -> stopAudioCapture()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Start capturing audio via AudioRecord from the AccessibilityService context.
     * This is called by PhoneMonitorService when a call is detected.
     */
    fun startAudioCapture() {
        if (isCapturing) {
            Log.d(TAG, "Already capturing audio")
            return
        }
        isCapturing = true
        Log.i(TAG, "Starting audio capture from AccessibilityService context")

        audioLoopJob = serviceScope.launch(Dispatchers.IO) {
            val model = VoskModelManager.getModel(this@VocaGuardAccessibilityService)
            if (model == null) {
                Log.e(TAG, "Vosk model unavailable")
                isCapturing = false
                return@launch
            }

            // Try multiple audio sources in order of preference
            val sourcesToTry = listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                MediaRecorder.AudioSource.DEFAULT to "DEFAULT"
            )

            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = minBuf.coerceAtLeast(8192)

            var record: AudioRecord? = null
            var usedSource = ""

            for ((source, name) in sourcesToTry) {
                try {
                    val r = AudioRecord(
                        source, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        record = r
                        usedSource = name
                        Log.i(TAG, "AudioRecord initialized with source=$name")
                        break
                    } else {
                        Log.w(TAG, "AudioRecord failed to init with source=$name")
                        r.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord source=$name error: ${e.message}")
                }
            }

            if (record == null) {
                Log.e(TAG, "All audio sources failed to initialize")
                isCapturing = false
                return@launch
            }

            val recognizer = Recognizer(model, sampleRate.toFloat())
            audioRecord = record
            voskRecognizer = recognizer
            val shortBuf = ShortArray(bufSize / 2)

            record.startRecording()
            Log.i(TAG, "Audio loop started (source=$usedSource) from AccessibilityService")

            var loopCount = 0
            var hasLoggedNonZeroRms = false
            while (isActive && isCapturing) {
                val read = record.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue

                loopCount++
                // Log RMS every ~2 seconds to verify audio capture
                if (loopCount % 30 == 0) {
                    var sum = 0L
                    for (i in 0 until read) sum += shortBuf[i].toLong() * shortBuf[i]
                    val rms = Math.sqrt(sum.toDouble() / read).toInt()
                    Log.d(TAG, "A11y Audio RMS=$rms (read=$read samples) loop=$loopCount source=$usedSource")

                    if (rms > 100 && !hasLoggedNonZeroRms) {
                        hasLoggedNonZeroRms = true
                        Log.i(TAG, "*** NON-ZERO AUDIO DETECTED from AccessibilityService! RMS=$rms ***")
                    }
                }

                if (recognizer.acceptWaveForm(shortBuf, read)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    if (text.isNotEmpty()) {
                        Log.i(TAG, "A11y Vosk result: $text")
                        // Forward recognized text to CallMonitoringService
                        forwardTextToMonitor(text)
                    }
                }
            }

            val finalText = JSONObject(recognizer.finalResult).optString("text").trim()
            if (finalText.isNotEmpty()) {
                Log.i(TAG, "A11y Vosk final: $finalText")
                forwardTextToMonitor(finalText)
            }

            record.stop()
            record.release()
            recognizer.close()
            audioRecord = null
            voskRecognizer = null
            Log.i(TAG, "A11y audio loop ended")
        }
    }

    fun stopAudioCapture() {
        if (!isCapturing) return
        Log.i(TAG, "Stopping audio capture")
        isCapturing = false
        audioLoopJob?.cancel()
        audioLoopJob = null
    }

    /**
     * Forward recognized text to CallMonitoringService for scam pattern analysis.
     */
    private fun forwardTextToMonitor(text: String) {
        try {
            val intent = Intent(this, CallMonitoringService::class.java).apply {
                action = "io.vocaguard.A11Y_TEXT"
                putExtra("text", text)
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot forward text to CallMonitoringService: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopAudioCapture()
        serviceScope.cancel()
        instance = null
        Log.i(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }
}
