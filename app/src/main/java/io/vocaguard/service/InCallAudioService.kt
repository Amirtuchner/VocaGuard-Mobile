package io.vocaguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.vocaguard.R
import io.vocaguard.ui.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Microphone-type foreground service that runs ONLY while a VoIP call is active.
 *
 * Android 11+ feeds silence to any AudioRecord owned by a backgrounded app unless
 * the app holds a foreground service of type "microphone" that was started while
 * the app was in the foreground. Without this service, the moment the user left
 * the call screen (home button, another app) the far end stopped hearing them,
 * even though they still heard the far end. The Linphone media engine itself keeps
 * running either way — only mic capture was being muted by the OS.
 *
 * Lifecycle: started by [IncomingCallActivity] when the SIP call goes ACTIVE (the
 * call screen is visible then, satisfying the while-in-use start restriction) and
 * stopped when the call ends. It captures nothing itself; it only grants the
 * process background-mic eligibility for the ongoing call.
 */
class InCallAudioService : Service() {

    // Watches the SIP call state directly: the activity's Compose effects are paused
    // while the app is backgrounded, so if the FAR END hangs up with the user away
    // from the app, only this observer reliably stops the service (and its ongoing
    // notification) right away.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcherJob: Job? = null

    companion object {
        private const val TAG = "InCallAudioService"
        private const val NOTIFICATION_ID = 1004
        private const val CHANNEL_ID = "in_call_audio_channel"
        /** Demo scam call (Play reviewer) has no SIP call, so the SIP-state watcher
         *  would stop the service instantly — this extra skips it. */
        private const val EXTRA_DEMO = "demo"

        fun start(context: Context, demo: Boolean = false) {
            context.startForegroundService(
                Intent(context, InCallAudioService::class.java).putExtra(EXTRA_DEMO, demo)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InCallAudioService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_DEMO, false) == true) {
            watcherJob?.cancel()
            watcherJob = null
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                // API 29: no mic FGS type constant, and no background-mic muting either.
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // Thrown if started while the app is not in the foreground (while-in-use
            // restriction). The call continues; only background mic stays muted.
            Log.e(TAG, "Cannot start in-call audio foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "In-call audio service started (microphone FGS)")
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        watcherJob = serviceScope.launch {
            VocaGuardSipManager.callState.collect { state ->
                if (state == VocaGuardSipManager.CallState.ENDED ||
                    state == VocaGuardSipManager.CallState.IDLE
                ) {
                    Log.i(TAG, "SIP call over ($state) — stopping in-call audio service")
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "In-call audio service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Call in progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        // Tapping returns to the call screen.
        val openCallPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, IncomingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Call in progress")
            .setContentText("VocaGuard is protecting this call — tap to return")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openCallPi)
            .setOngoing(true)
            .build()
    }
}
