package io.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.service.PhoneMonitorService

/**
 * Manifest-registered receiver for [TelephonyManager.ACTION_PHONE_STATE_CHANGED].
 *
 * Samsung devices aggressively freeze background processes, which causes
 * [android.telephony.TelephonyCallback] to silently stop delivering events
 * even when a foreground service is running. This receiver is woken by the
 * system broadcast regardless of process state, ensuring call detection
 * always works.
 *
 * It delegates to [PhoneMonitorService] which hosts the actual monitoring logic.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        Log.i(TAG, "Phone state broadcast: $state")

        // IMPORTANT: do NOT start PhoneMonitorService from here. It is a shortService
        // (restricted) foreground service, and Android 15 forbids launching restricted
        // foreground-service types from a background-woken manifest broadcast receiver
        // — Play flags it as a crash risk ("Limited types of services running in the
        // foreground"). The service is started on the exempt paths only: the FCM
        // incoming_call push (which carries the FGS-start grant) and the foreground
        // IncomingCallActivity. This receiver only updates shared call state and
        // forwards transitions to the service when it is ALREADY running.
        val scamDatabaseManager = ScamDatabaseManager.getInstance(context)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.i(TAG, "Incoming call detected via broadcast")
                scamDatabaseManager.markCallRinging()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.i(TAG, "Call answered — notifying PhoneMonitorService")
                scamDatabaseManager.resolveActiveCallDirectionOnOffhook()
                forwardIfRunning(context, PhoneMonitorService.ACTION_CALL_OFFHOOK)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Call ended — notifying PhoneMonitorService")
                scamDatabaseManager.markCallIdle()
                forwardIfRunning(context, PhoneMonitorService.ACTION_CALL_IDLE)
            }
        }
    }

    /**
     * Deliver a call-state action to PhoneMonitorService using startService (NOT
     * startForegroundService, so it never launches a restricted FGS). If the service
     * isn't already running, a background startService throws — swallow it, since
     * there's nothing to notify in that case.
     */
    private fun forwardIfRunning(context: Context, action: String) {
        try {
            context.startService(
                Intent(context, PhoneMonitorService::class.java).apply { this.action = action }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Not forwarding $action (service not running): ${e.message}")
        }
    }
}
