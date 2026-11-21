package io.vocaguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.vocaguard.service.DidSyncWorker

/**
 * Fires once right after a Play Store update installs. Users rarely open a
 * protection app, so without this a background update would never schedule the
 * daily [DidSyncWorker] — and a server-side DID change would never reach them.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DidSyncWorker.schedule(context)
        }
    }
}
