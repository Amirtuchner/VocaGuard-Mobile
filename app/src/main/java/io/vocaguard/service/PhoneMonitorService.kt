package io.vocaguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.vocaguard.R
import io.vocaguard.monitor.PhoneStateMonitor
import io.vocaguard.service.CallMonitoringService

/**
 * Persistent foreground service that keeps [PhoneStateMonitor] alive so incoming
 * calls are detected even when the app is in the background.
 *
 * On Samsung Galaxy devices Samsung's Telecom stack does not always invoke
 * [android.telecom.CallScreeningService], so this service acts as the primary
 * call-detection path via TelephonyCallback / PhoneStateListener.
 */
class PhoneMonitorService : Service() {

    companion object {
        private const val TAG = "PhoneMonitorService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "phone_monitor_channel"
        const val ACTION_CALL_OFFHOOK = "io.vocaguard.ACTION_CALL_OFFHOOK"
        const val ACTION_CALL_IDLE = "io.vocaguard.ACTION_CALL_IDLE"
    }

    private lateinit var phoneStateMonitor: PhoneStateMonitor
    private var isCallActive = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastPolledState = -1
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        phoneStateMonitor = PhoneStateMonitor(
            context = this,
            onCallStarted = { handleCallAnswered() },
            onCallEnded = { handleCallEnded() }
        )
    }

    /** Try to upgrade the running FGS to include microphone type (needed for AudioRecord/SpeechRecognizer). */
    private fun upgradeFgsForMicrophone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                Log.i(TAG, "FGS upgraded to include microphone type")
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot upgrade FGS for microphone: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle broadcast-forwarded call state actions (from PhoneStateReceiver)
        when (intent?.action) {
            ACTION_CALL_OFFHOOK -> {
                Log.i(TAG, "OFFHOOK from broadcast receiver — starting monitoring")
                handleCallAnswered()
                return START_STICKY
            }
            ACTION_CALL_IDLE -> {
                Log.i(TAG, "IDLE from broadcast receiver — stopping monitoring")
                handleCallEnded()
                return START_STICKY
            }
        }

        val notification = buildNotification()
        val hasAudio = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val type = if (hasAudio)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot start with microphone type, using dataSync only: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } catch (e2: Exception) {
                    Log.e(TAG, "Cannot start foreground at all: ${e2.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        phoneStateMonitor.startMonitoring()
        acquireWakeLock()
        startCallStatePolling()
        Log.i(TAG, "Phone monitor service started")
        return START_STICKY
    }

    private var pollCount = 0

    /** Polls every 2s to detect active calls via multiple APIs. */
    private val callStatePollRunnable = object : Runnable {
        override fun run() {
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val audioMode = audioManager.mode

            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val callState = tm.callState

            // TelecomManager.isInCall() — most reliable on Samsung
            val telecomManager = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            val telecomInCall = try { telecomManager.isInCall } catch (_: SecurityException) { false }

            val inCall = telecomInCall
                || audioMode == android.media.AudioManager.MODE_IN_CALL
                || audioMode == android.media.AudioManager.MODE_IN_COMMUNICATION
                || callState == TelephonyManager.CALL_STATE_OFFHOOK
                || callState == TelephonyManager.CALL_STATE_RINGING

            val newState = if (inCall) TelephonyManager.CALL_STATE_OFFHOOK
                else TelephonyManager.CALL_STATE_IDLE

            // Log every 5th poll for debugging
            pollCount++
            if (pollCount % 5 == 0) {
                Log.d(TAG, "Poll #$pollCount: audioMode=$audioMode callState=$callState telecom=$telecomInCall inCall=$inCall")
            }

            if (newState != lastPolledState) {
                Log.i(TAG, "Poll state change: audioMode=$audioMode callState=$callState telecom=$telecomInCall inCall=$inCall")
                lastPolledState = newState
                when (newState) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> handleCallAnswered()
                    TelephonyManager.CALL_STATE_IDLE -> handleCallEnded()
                }
            }
            pollHandler.postDelayed(this, 2000)
        }
    }

    private fun startCallStatePolling() {
        pollHandler.removeCallbacks(callStatePollRunnable)
        pollHandler.post(callStatePollRunnable)
    }

    private fun stopCallStatePolling() {
        pollHandler.removeCallbacks(callStatePollRunnable)
    }

    /** Called when the call is answered — either by TelephonyCallback or broadcast receiver. */
    private fun handleCallAnswered() {
        if (isCallActive) return
        isCallActive = true

        val settings = io.vocaguard.data.DetectionSettings.getInstance(this)
        if (!settings.callForwardingEnabled) {
            Log.i(TAG, "Call answered but call forwarding not enabled — skipping")
            isCallActive = false
            return
        }

        upgradeFgsForMicrophone()
        Log.i(TAG, "Starting CallMonitoringService (embedded in PhoneMonitorService FGS)")
        startService(
            Intent(this, CallMonitoringService::class.java).apply {
                action = CallMonitoringService.ACTION_START_MONITORING
                putExtra(CallMonitoringService.EXTRA_SKIP_FOREGROUND, true)
            }
        )

        // Also start audio capture from AccessibilityService context —
        // Samsung Android 15 blocks AudioRecord from regular FGS during calls,
        // but AccessibilityService may have elevated privileges.
        VocaGuardAccessibilityService.instance?.let {
            Log.i(TAG, "Starting audio capture via AccessibilityService")
            it.startAudioCapture()
        } ?: Log.w(TAG, "AccessibilityService not running — a11y audio capture unavailable")
    }

    /** Called when the call ends — either by TelephonyCallback or broadcast receiver. */
    private fun handleCallEnded() {
        if (!isCallActive) return
        isCallActive = false
        // Stop AccessibilityService audio capture
        VocaGuardAccessibilityService.instance?.stopAudioCapture()
        startService(
            Intent(this, CallMonitoringService::class.java).apply {
                action = CallMonitoringService.ACTION_STOP_MONITORING
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallStatePolling()
        releaseWakeLock()
        phoneStateMonitor.stopMonitoring()
        Log.i(TAG, "Phone monitor service stopped")
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VocaGuard::PhoneMonitorWakeLock"
            ).apply { acquire() }
            Log.i(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            Log.i(TAG, "WakeLock released")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Call Protection",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VocaGuard")
            .setContentText("Monitoring for scam calls")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
