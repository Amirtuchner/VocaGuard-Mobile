package com.example.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.vocaguard.data.ScamDatabaseManager
import com.example.vocaguard.data.ScamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Block Caller" action tapped directly from a scam alert notification.
 * Reports the number to [ScamDatabaseManager] so future calls are blocked immediately.
 *
 * Uses [goAsync] so the IO work can complete after [onReceive] returns — without it,
 * the process may be killed before the Room insert finishes.
 */
class BlockCallerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BlockCallerReceiver"
        const val ACTION_BLOCK_CALLER = "com.example.vocaguard.ACTION_BLOCK_CALLER"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_SCAM_TYPE = "extra_scam_type"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BLOCK_CALLER) return

        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        if (phoneNumber.isNullOrBlank()) {
            Log.w(TAG, "Block-caller action received with no phone number")
            return
        }

        val scamType = intent.getStringExtra(EXTRA_SCAM_TYPE)
            ?.let { runCatching { ScamType.valueOf(it) }.getOrNull() }
            ?: ScamType.UNKNOWN

        // Keep the broadcast alive until the IO work completes.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScamDatabaseManager.getInstance(context).reportScamNumber(phoneNumber, scamType)
                Log.i(TAG, "Blocked caller $phoneNumber ($scamType) from notification action")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
