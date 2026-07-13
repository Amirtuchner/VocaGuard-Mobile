package io.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
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

        // Ensure PhoneMonitorService is running — it handles the actual
        // call monitoring logic via its PhoneStateMonitor instance.
        try {
            context.startForegroundService(
                Intent(context, PhoneMonitorService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start PhoneMonitorService: ${e.message}")
        }

        // Forward the state change to PhoneMonitorService so it can act
        // even if its TelephonyCallback was frozen.
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.i(TAG, "Incoming call detected via broadcast")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.i(TAG, "Call answered — notifying PhoneMonitorService")
                context.startService(
                    Intent(context, PhoneMonitorService::class.java).apply {
                        action = "io.vocaguard.ACTION_CALL_OFFHOOK"
                    }
                )
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Call ended — notifying PhoneMonitorService")
                context.startService(
                    Intent(context, PhoneMonitorService::class.java).apply {
                        action = "io.vocaguard.ACTION_CALL_IDLE"
                    }
                )
            }
        }
    }
}
