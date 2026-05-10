package io.vocaguard.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.vocaguard.alert.ScamAlertManager
import io.vocaguard.data.DetectionSettings
import io.vocaguard.detection.HybridScamDetector
import io.vocaguard.ui.ScamOverlayManager

/**
 * Monitors WhatsApp and Telegram notifications for scam message patterns.
 *
 * When a notification from a watched app arrives, the message text is extracted
 * and analysed by [ScamPatternDetector]. On a positive detection the notification
 * is cancelled (so the user is not prompted to open or reply to it), the existing
 * alert pipeline is fired (sound / vibration / TTS / family alert), and an overlay
 * warning is shown if SYSTEM_ALERT_WINDOW is granted.
 *
 * Limitation: notification text is only available when the user has "Show
 * notifications" / "Show message preview" enabled in WhatsApp / Telegram. If
 * previews are off the notification body will be blank and scanning is skipped.
 *
 * The service must be granted Notification Access by the user via
 * Settings → Apps → Special app access → Notification access.
 */
class MessagingScamDetectorService : NotificationListenerService() {

    companion object {
        private const val TAG = "MessagingScamDetector"

        private val WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",               // WhatsApp Business
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
        )

        /**
         * Returns true if VocaGuard has been granted Notification Access and this
         * service is currently listed as an enabled notification listener.
         */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, MessagingScamDetectorService::class.java)
            return flat.contains(cn.flattenToString())
        }
    }

    private lateinit var detector: HybridScamDetector
    private lateinit var alertManager: ScamAlertManager
    private lateinit var overlayManager: ScamOverlayManager

    override fun onCreate() {
        super.onCreate()
        detector = HybridScamDetector(this)
        alertManager = ScamAlertManager(this)
        overlayManager = ScamOverlayManager(this)
    }

    override fun onDestroy() {
        detector.release()
        alertManager.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = DetectionSettings.getInstance(this)
        if (!settings.messageScanEnabled) return
        if (sbn.packageName !in WATCHED_PACKAGES) return

        val text = extractText(sbn.notification) ?: return
        if (text.isBlank()) return

        val result = detector.analyzeText(text)
        if (!result.isScam) return

        Log.w(
            TAG,
            "Scam message detected from ${sbn.packageName}: " +
                "${result.scamType} (confidence=${result.confidence})"
        )

        // Dismiss the notification so the user is not prompted to open it.
        cancelNotification(sbn.key)

        // Fire the existing alert pipeline (sound, vibration, TTS, VocaGuard
        // notification, family alert).
        alertManager.triggerScamAlert(
            scamType   = result.scamType,
            transcript = text.take(500),
            confidence = result.confidence,
            phoneNumber = ""
        )

        // Overlay warning on top of whatever app is in the foreground.
        overlayManager.show(result.scamType, result.confidence)
    }

    /**
     * Extracts the most informative text string from a notification, trying
     * several extras in priority order.
     */
    private fun extractText(notification: Notification): String? {
        val extras: Bundle = notification.extras

        // MessagingStyle — used by WhatsApp and Telegram for individual/group chats.
        // Each message is a Bundle with a "text" key.
        @Suppress("UNCHECKED_CAST")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null) {
            val joined = messages.mapNotNull { msg ->
                (msg as? Bundle)?.getCharSequence("text")?.toString()
            }.joinToString(" ")
            if (joined.isNotBlank()) return joined
        }

        // BigTextStyle fallback (long single message).
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        if (!bigText.isNullOrBlank()) return bigText.toString()

        // Standard title + body (most common for simple notifications).
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return "$title $body".trim().takeIf { it.isNotBlank() }
    }
}