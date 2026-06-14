package io.vocaguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
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
 */
class PhoneMonitorService : Service() {

    companion object {
        private const val TAG = "PhoneMonitorService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "phone_monitor_channel"
    }

    private lateinit var phoneStateMonitor: PhoneStateMonitor

    override fun onCreate() {
        super.onCreate()
        phoneStateMonitor = PhoneStateMonitor(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        phoneStateMonitor.startMonitoring()
        Log.i(TAG, "Phone monitor service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
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
