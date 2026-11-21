package io.vocaguard.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import io.vocaguard.MainActivity
import io.vocaguard.R
import io.vocaguard.data.DetectionSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Daily silent re-registration with the server, so a server-side DID change reaches
 * the handset. The server can move a user to a new dedicated DID (pool migration,
 * e.g. after the 2026-11 shared-DID bug); call forwarding (*21*) is set on the
 * handset, so the app must re-dial it to the new DID or calls keep going to the old one.
 *
 * Re-forwarding is tried silently via USSD first. If the carrier rejects that (common
 * for call-forwarding codes), a flag is set and a notification asks the user to open
 * the app, where [MainActivity] offers a one-tap "Update" that dials the code.
 */
class DidSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "DidSync"
        private const val WORK_NAME = "did_sync"
        private const val PREFS = "did_sync"
        private const val KEY_PENDING = "forwarding_update_pending"
        private const val CHANNEL_ID = "forwarding_update"
        private const val NOTIFICATION_ID = 3101

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DidSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun isForwardingUpdatePending(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

        fun clearForwardingUpdatePending(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PENDING).apply()
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    override suspend fun doWork(): Result {
        ServerDetectionManager.init(applicationContext)
        if (!ServerDetectionManager.isRegistered()) return Result.success()
        val phone = ServerDetectionManager.getPhoneNumber()
        if (phone.isEmpty()) return Result.success()

        val fcmToken = try {
            Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "FCM token unavailable: ${e.message}")
            return Result.retry()
        }

        val oldDid = ServerDetectionManager.getDidNumber().trimStart('+')
        val result = ServerDetectionManager.register(phone, fcmToken)
        if (!result.success) {
            Log.w(TAG, "re-register failed: ${result.error}")
            return Result.retry()
        }
        val newDid = result.didNumber.trimStart('+')
        if (newDid.isEmpty() || newDid == oldDid) return Result.success()

        Log.i(TAG, "server DID changed $oldDid -> $newDid")
        if (!DetectionSettings.getInstance(applicationContext).callForwardingEnabled) {
            // Forwarding not set up yet — onboarding/settings will use the new DID.
            return Result.success()
        }
        val code = ServerDetectionManager.getActivationCode(applicationContext)
        val silentOk = withTimeoutOrNull(15_000) { trySilentUssd(code) } ?: false
        if (silentOk) {
            Log.i(TAG, "re-forwarded silently to $newDid")
            clearForwardingUpdatePending(applicationContext)
        } else {
            Log.w(TAG, "silent re-forward rejected — asking user")
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PENDING, true).apply()
            notifyUpdateNeeded()
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun trySilentUssd(code: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val tm = applicationContext.getSystemService(TelephonyManager::class.java)
                tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: TelephonyManager, r: String, resp: CharSequence) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onReceiveUssdResponseFailed(t: TelephonyManager, r: String, code: Int) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "USSD exception: $e")
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun notifyUpdateNeeded() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call forwarding updates", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Asks you to update call forwarding when your protection number changes" }
            )
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Action needed: keep VocaGuard protecting your calls")
            .setContentText("Tap to update your call forwarding. It takes one tap.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (e: SecurityException) { /* notifications denied */ }
    }
}
