package io.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.vocaguard.data.ScamDatabaseManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Pre-load the scam database so lookups are fast on the first call.
            // We do NOT start a foreground service here: the call-protection service
            // is now a shortService, which cannot be launched from the background at
            // boot. Incoming monitored calls are delivered by FCM push, which wakes
            // the app and starts the shortService at that point.
            Log.i(TAG, "Device booted - pre-loading VocaGuard scam database")
            ScamDatabaseManager.getInstance(context)
        }
    }
}
