package io.vocaguard.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.vocaguard.alert.ScamAlertManager
import io.vocaguard.data.ContactsHelper
import io.vocaguard.data.DetectionSettings
import io.vocaguard.detection.HybridScamDetector
import io.vocaguard.ui.ScamOverlayManager

/**
 * Monitors WhatsApp and Telegram notifications for scam message patterns.
 *
 * When a notification from a watched app arrives, the message text is extracted
 * and analysed by [HybridScamDetector]. On a positive detection the notification
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

        /**
         * Minimum confidence required to fire an alert for a scanned message, regardless of
         * the user's sensitivity setting. Messages are scanned far more frequently than calls
         * (every incoming WhatsApp/Telegram notification), so we apply a stricter floor to
         * prevent low-confidence ensemble results from causing false-positive alerts.
         */
        private const val MIN_MESSAGE_CONFIDENCE = 0.65f

        /**
         * Minimum message text length to bother analysing. Very short messages (greetings,
         * emoji, one-word replies) cannot contain enough signal to reliably detect a scam.
         */
        private const val MIN_TEXT_LENGTH = 30

        private val WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",               // WhatsApp Business
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "com.facebook.orca",              // Facebook Messenger
            "com.facebook.mlite",             // Messenger Lite
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

        /**
         * Extracts the most informative text string from a notification, trying
         * several extras in priority order. Exposed as internal so it can be
         * unit-tested without a live service instance.
         *
         * For MessagingStyle (WhatsApp / Telegram), only the **last** (most recent)
         * message is returned. Joining all messages in the bundle risks accumulating
         * keywords across the entire conversation history and inflating the scam score.
         */
        internal fun extractText(notification: Notification): String? {
            val extras: Bundle = notification.extras

            // MessagingStyle — used by WhatsApp and Telegram for individual/group chats.
            // Each message is a Bundle with a "text" key; the last entry is the newest message.
            val messages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            }
            if (messages != null) {
                // Only analyse the most recent message to avoid keyword accumulation across history.
                val lastText = messages.lastOrNull()?.let { msg ->
                    (msg as? Bundle)?.getCharSequence("text")?.toString()
                }
                if (!lastText.isNullOrBlank()) return lastText
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

    /**
     * Extracts the sender's display name from a MessagingStyle notification.
     * Returns the last message's sender name if available, otherwise the notification title.
     * For group chats where the title is the group name, this returns the group name —
     * group-chat messages are still scanned since the individual sender is unknown.
     */
    private fun extractSender(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        if (messages != null && messages.isNotEmpty()) {
            val lastMsg = (messages.last() as? Bundle)
            val senderName = lastMsg?.getCharSequence("sender")?.toString()
            if (!senderName.isNullOrBlank()) return senderName
        }
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = DetectionSettings.getInstance(this)
        if (!settings.messageScanEnabled) return
        if (sbn.packageName !in WATCHED_PACKAGES) return

        // Skip known contacts — scammers are never saved in the victim's contact list.
        val sender = extractSender(sbn)
        if (sender.isNotBlank() &&
            (ContactsHelper.isKnownContactByName(this, sender) ||
             ContactsHelper.isKnownContact(this, sender))) {
            Log.d(TAG, "Skipping scam check — sender '$sender' is a known contact")
            return
        }

        val text = extractText(sbn.notification) ?: return
        if (text.length < MIN_TEXT_LENGTH) return

        val result = detector.analyzeMessage(text)

        // Require isScam AND a confidence above the message-scanning floor.
        // The ensemble threshold (user sensitivity) may be as low as 0.40; for messages we
        // enforce a stricter minimum to avoid false-positive alerts on normal chat.
        if (!result.isScam || result.confidence < MIN_MESSAGE_CONFIDENCE) return

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
}