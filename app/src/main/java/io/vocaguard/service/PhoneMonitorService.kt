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
 *
 * Battery optimization: no wake lock, no polling when idle.
 * TelephonyCallback/PhoneStateListener deliver events without polling.
 * Polling is only activated during an active call to catch edge cases.
 */
class PhoneMonitorService : Service() {

    companion object {
        private const val TAG = "PhoneMonitorService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "phone_monitor_channel"
        const val ACTION_CALL_OFFHOOK = "io.vocaguard.ACTION_CALL_OFFHOOK"
        const val ACTION_CALL_IDLE = "io.vocaguard.ACTION_CALL_IDLE"
        /** Poll interval during an active call — only runs while call is in progress. */
        private const val ACTIVE_CALL_POLL_MS = 5_000L
    }

    private lateinit var phoneStateMonitor: PhoneStateMonitor
    private var isCallActive = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastPolledState = -1

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
                Log.w(TAG, "Cannot start with microphone type, using specialUse only: ${e.message}")
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
        // No wake lock, no polling at startup — TelephonyCallback delivers events.
        // Polling is only started during active calls as a Samsung fallback.
        Log.i(TAG, "Phone monitor service started (battery-optimized, no wake lock)")
        return START_STICKY
    }

    private var pollCount = 0

    /**
     * Polls every [ACTIVE_CALL_POLL_MS] ms to detect call-end on Samsung devices
     * that sometimes miss TelephonyCallback IDLE events. Only runs while a call
     * is in progress — zero battery impact when idle.
     */
    private val callStatePollRunnable = object : Runnable {
        override fun run() {
            if (!isCallActive) return  // stop polling when call ended

            val telecomManager = getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager
            val telecomInCall = try { telecomManager.isInCall } catch (_: SecurityException) { false }

            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val callState = tm.callState

            val inCall = telecomInCall
                || callState == TelephonyManager.CALL_STATE_OFFHOOK
                || callState == TelephonyManager.CALL_STATE_RINGING

            pollCount++
            if (pollCount % 6 == 0) {
                Log.d(TAG, "Active-call poll #$pollCount: callState=$callState telecom=$telecomInCall")
            }

            if (!inCall) {
                Log.i(TAG, "Poll detected call ended")
                handleCallEnded()
                return  // stop polling
            }
            pollHandler.postDelayed(this, ACTIVE_CALL_POLL_MS)
        }
    }

    private fun startActiveCallPolling() {
        pollCount = 0
        pollHandler.removeCallbacks(callStatePollRunnable)
        pollHandler.postDelayed(callStatePollRunnable, ACTIVE_CALL_POLL_MS)
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

        // When the SIP bridge is active, the server handles scam detection via
        // scam_detector_bg.py. Starting local AudioRecord/Vosk would compete with
        // Linphone for the mic, causing periodic audio clicks/pops.
        val sipState = VocaGuardSipManager.callState.value
        if (sipState == VocaGuardSipManager.CallState.ACTIVE ||
            sipState == VocaGuardSipManager.CallState.INCOMING) {
            Log.i(TAG, "SIP bridge active (state=$sipState) — skipping local audio monitoring")
            isCallActive = false
            return
        }

        // Start polling only during active calls — catches Samsung IDLE misses
        startActiveCallPolling()

        upgradeFgsForMicrophone()
        Log.i(TAG, "Starting CallMonitoringService (embedded in PhoneMonitorService FGS)")
        startService(
            Intent(this, CallMonitoringService::class.java).apply {
                action = CallMonitoringService.ACTION_START_MONITORING
                putExtra(CallMonitoringService.EXTRA_SKIP_FOREGROUND, true)
            }
        )
    }

    /** Called when the call ends — either by TelephonyCallback or broadcast receiver. */
    private fun handleCallEnded() {
        if (!isCallActive) return
        isCallActive = false
        stopCallStatePolling()
        startService(
            Intent(this, CallMonitoringService::class.java).apply {
                action = CallMonitoringService.ACTION_STOP_MONITORING
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallStatePolling()
        phoneStateMonitor.stopMonitoring()
        Log.i(TAG, "Phone monitor service stopped")
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
