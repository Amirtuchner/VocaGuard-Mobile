package io.vocaguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import io.vocaguard.R
import io.vocaguard.monitor.PhoneStateMonitor

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
        // v2 channel at IMPORTANCE_LOW so the specialUse foreground-service
        // notification is actually visible in the shade (the old MIN channel hid it,
        // and channel importance can't be raised after creation — hence a new id).
        private const val CHANNEL_ID = "call_protection_channel_v2"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // This service is specialUse ONLY (declared that way in the manifest) so
            // it is legal to start from the BOOT_COMPLETED receiver — Android 15
            // forbids launching a microphone-type FGS from boot. The app no longer
            // uses an on-device microphone FGS at all (detection is server-side).
            try {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start foreground service: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
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

        // Start polling only during active calls — catches Samsung IDLE misses.
        // Scam detection itself runs server-side via the SIP bridge
        // (scam_detector_bg.py); there is no on-device microphone monitoring.
        startActiveCallPolling()
    }

    /** Called when the call ends — either by TelephonyCallback or broadcast receiver. */
    private fun handleCallEnded() {
        if (!isCallActive) return
        isCallActive = false
        stopCallStatePolling()
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
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VocaGuard — Scam protection active")
            .setContentText("Monitoring your calls for scams")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
