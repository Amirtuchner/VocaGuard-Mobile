package com.example.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.vocaguard.data.ScamDatabaseManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted - pre-loading VocaGuard scam database")
            // Eagerly initialize the singleton so scam database is loaded and ready
            // when ScamCallScreeningService or CallAudioAccessibilityService first fires.
            // The actual call monitoring services are system-bound and restart automatically.
            ScamDatabaseManager.getInstance(context)
        }
    }
}