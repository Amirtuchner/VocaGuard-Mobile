package io.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.service.PhoneMonitorService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted - pre-loading VocaGuard scam database")
            ScamDatabaseManager.getInstance(context)
            context.startForegroundService(Intent(context, PhoneMonitorService::class.java))
        }
    }
}
