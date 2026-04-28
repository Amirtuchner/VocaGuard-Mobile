package io.vocaguard.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.vocaguard.R
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that runs community scam blocklist sync in the background.
 * Scheduled once every 24 hours; uses [CommunityScamSync]'s own cache-freshness
 * check so it is a no-op if the data is already up to date.
 */
class CommunityScamSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CommunityScamSyncWorker"
        private const val WORK_NAME = "community_scam_sync"
        private const val CHANNEL_ID = "community_sync_channel"
        private const val NOTIFICATION_ID = 3001

        /**
         * Enqueue a periodic sync if one is not already scheduled.
         * Safe to call multiple times (uses [ExistingPeriodicWorkPolicy.KEEP]).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CommunityScamSyncWorker>(
                24, TimeUnit.HOURS,
                4, TimeUnit.HOURS  // flex: run any time in the last 4 h of the 24-h period
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Periodic community sync scheduled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running background community sync")
        val count = CommunityScamSync.getInstance(applicationContext).sync(force = false)
        return if (count >= 0) {
            Log.i(TAG, "Background sync complete: $count numbers imported")
            if (count > 0) notifySyncComplete(count)
            Result.success()
        } else {
            Log.w(TAG, "Background sync failed, will retry")
            Result.retry()
        }
    }

    private fun notifySyncComplete(count: Int) {
        val notificationManager =
            applicationContext.getSystemService(NotificationManager::class.java) ?: return

        // Ensure the channel exists
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Blocklist Updates",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Notifies when the community scam blocklist is updated" }
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Blocklist updated")
            .setContentText("$count scam numbers added from the community blocklist.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
