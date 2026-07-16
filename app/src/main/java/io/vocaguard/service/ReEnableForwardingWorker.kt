package io.vocaguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.vocaguard.R
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ReEnableForwardingWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "ReEnableForwarding"
        const val WORK_NAME = "re_enable_call_forwarding"
        private const val CHANNEL_ID = "forwarding_reminder"
        private const val NOTIF_ID = 3001
    }

    override suspend fun doWork(): Result {
        ServerDetectionManager.init(ctx)
        val code = ServerDetectionManager.getActivationCode()
        if (code.isEmpty()) {
            Log.w(TAG, "No activation code — skipping")
            return Result.success()
        }

        val ussdOk = trySilentUssd(code)
        if (!ussdOk) {
            Log.w(TAG, "USSD failed — showing notification")
            showNotification(code)
        }
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun trySilentUssd(code: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val tm = ctx.getSystemService(TelephonyManager::class.java)
                tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        tm: TelephonyManager, request: String, response: CharSequence
                    ) {
                        Log.i(TAG, "USSD re-enable success: $response")
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onReceiveUssdResponseFailed(
                        tm: TelephonyManager, request: String, failureCode: Int
                    ) {
                        Log.w(TAG, "USSD re-enable failed: $failureCode")
                        if (cont.isActive) cont.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "USSD exception: $e")
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun showNotification(code: String) {
        val callIntent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", code, null))
        val pi = PendingIntent.getActivity(
            ctx, 0, callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call Forwarding", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Re-enable call forwarding after each call" }
            )
        }
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Re-enable Call Protection")
            .setContentText("Call forwarding was disabled. Tap to re-enable.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_launcher_foreground, "Re-enable Now", pi)
            .build()
        )
    }
}
