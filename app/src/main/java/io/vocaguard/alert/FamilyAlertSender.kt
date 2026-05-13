package io.vocaguard.alert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import io.vocaguard.BuildConfig
import io.vocaguard.data.FamilyContact
import io.vocaguard.data.FamilyGuardSettings
import io.vocaguard.data.ScamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notifies family members / caregivers when a scam call is detected on the senior's device.
 *
 * Two channels:
 *  1. **SMS** — sent to every contact in [FamilyGuardSettings.contacts]. The message includes a
 *     `vocaguard://alert` deep-link so that caregivers who also have VocaGuard installed can tap it
 *     to import the alert directly into their [FamilyDashboard].
 *  2. **Webhook** — if [FamilyGuardSettings.webhookUrl] is set, a JSON POST is made to that URL
 *     (compatible with ntfy.sh, Pushover, IFTTT, or any custom server).
 *
 * Both channels are opt-in: SMS requires the `SEND_SMS` permission and Family Guard Mode to be
 * enabled; webhook requires a non-empty URL. Failures are logged but never crash the caller.
 */
class FamilyAlertSender(private val context: Context) {

    companion object {
        private const val TAG = "FamilyAlertSender"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    private val settings = FamilyGuardSettings.getInstance(context)

    /**
     * Sends SMS and/or webhook notifications for a detected [scamType] with [confidence].
     * Call this from within a coroutine — network I/O runs on [Dispatchers.IO].
     *
     * @param callerNumber The phone number of the suspicious caller (may be empty if unknown).
     */
    suspend fun sendAlert(
        scamType: ScamType,
        confidence: Float,
        callerNumber: String = "",
        customMessage: String = ""
    ) {
        if (!settings.isEnabled) return
        val contacts = settings.contacts
        if (contacts.isEmpty()) return

        val senderName = settings.seniorName.ifBlank { "a family member" }
        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val confidencePct = (confidence * 100).toInt()
        val scamLabel = scamType.displayName()

        contacts.forEach { contact ->
            sendSms(contact, senderName, scamLabel, confidencePct, timeStr, scamType, confidence, customMessage)
        }

        val webhookUrl = settings.webhookUrl
        if (webhookUrl.isNotBlank()) {
            sendWebhook(webhookUrl, senderName, scamLabel, confidencePct, scamType, confidence, callerNumber)
        }
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private suspend fun sendSms(
        contact: FamilyContact,
        senderName: String,
        scamLabel: String,
        confidencePct: Int,
        timeStr: String,
        scamType: ScamType,
        confidence: Float,
        customMessage: String = ""
    ) = withContext(Dispatchers.IO) {
        try {
            val deepLink = buildDeepLink(senderName, scamType, confidence)
            val message = buildString {
                if (customMessage.isNotBlank()) {
                    append("[VocaGuard] $customMessage\n\n")
                    append("Tap to view in VocaGuard:\n$deepLink")
                } else {
                    append("[VocaGuard] SCAM ALERT\n")
                    append("$senderName's phone detected a $scamLabel at $timeStr ($confidencePct% confidence).\n\n")
                    append("Tap to view in VocaGuard:\n$deepLink")
                }
            }

            @Suppress("DEPRECATION")
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()

            // Use sendMultipartTextMessage to handle messages longer than 160 chars
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                contact.phoneNumber, null, parts, null, null
            )
            Log.i(TAG, "SMS alert sent to ${contact.name} (${contact.phoneNumber})")
        } catch (e: SecurityException) {
            Log.e(TAG, "SEND_SMS permission denied — grant it in Settings", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to ${contact.name}", e)
        }
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    private suspend fun sendWebhook(
        url: String,
        senderName: String,
        scamLabel: String,
        confidencePct: Int,
        scamType: ScamType,
        confidence: Float,
        callerNumber: String
    ) = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            Log.w(TAG, "Webhook URL must use HTTPS. Skipping.")
            return@withContext
        }
        try {
            val payload = JSONObject().apply {
                put("event", "scam_detected")
                put("senderName", senderName)
                put("scamType", scamType.name)
                put("scamLabel", scamLabel)
                put("confidencePct", confidencePct)
                put("callerNumber", callerNumber)
                put("timestamp", System.currentTimeMillis())
                put("appVersion", BuildConfig.VERSION_NAME)
            }.toString()

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            // ntfy.sh title/priority headers (ignored by other services)
            connection.setRequestProperty("Title", "⚠️ VocaGuard: $scamLabel detected")
            connection.setRequestProperty("Priority", "urgent")

            try {
                connection.outputStream.bufferedWriter().use { it.write(payload) }
                val code = connection.responseCode
                if (code in 200..299) {
                    Log.i(TAG, "Webhook delivered (HTTP $code)")
                } else {
                    Log.w(TAG, "Webhook returned HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webhook delivery failed", e)
        }
    }

    // ── Phone call alert ──────────────────────────────────────────────────────

    /**
     * Dials the primary family contact (first in the list) and plays a TTS voice message
     * once the call is placed. This is called *after* the scam call ends so it does not
     * interfere with the active call detection.
     *
     * Flow:
     *  1. Initiates an outgoing call via [Intent.ACTION_CALL].
     *  2. Waits 8 seconds for the caregiver to answer.
     *  3. Plays a TTS message through the device speaker so the caregiver hears it.
     *
     * Requires CALL_PHONE permission and [FamilyGuardSettings.callAlertEnabled] = true.
     *
     * @param scamType  The type of scam that was detected.
     * @param confidence  Detection confidence (0.0–1.0).
     * @param delayBeforeCallMs  How long to wait before dialling (default 5 s, giving the
     *                           senior time to end the scam call first).
     */
    suspend fun makeCallAlert(
        scamType: ScamType,
        confidence: Float,
        delayBeforeCallMs: Long = 5_000L
    ) {
        if (!settings.callAlertEnabled) return
        val contact = settings.contacts.firstOrNull() ?: return

        delay(delayBeforeCallMs)

        try {
            val dialIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            Log.i(TAG, "Phone call alert initiated to ${contact.name} (${contact.phoneNumber})")
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission denied — grant it in App Permissions", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate phone call alert", e)
            return
        }

        // Wait for the caregiver to answer, then play TTS voice message
        delay(8_000L)
        speakCallAlertMessage(scamType, confidence)
    }

    private fun speakCallAlertMessage(scamType: ScamType, confidence: Float) {
        val senderName = settings.seniorName.ifBlank { "your family member" }
        val scamLabel = scamType.displayName()
        val confidencePct = (confidence * 100).toInt()

        val message = "VocaGuard alert. $senderName's phone detected a $scamLabel with " +
                "$confidencePct percent confidence. Please check on them. " +
                "This is an automated alert from VocaGuard."

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "familyCallAlert")
                Log.i(TAG, "TTS voice message played on call alert")
            } else {
                Log.w(TAG, "TTS unavailable for call alert")
            }
        }
    }

    // ── Deep link builder ─────────────────────────────────────────────────────

    private fun buildDeepLink(
        senderName: String,
        scamType: ScamType,
        confidence: Float
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "vocaguard://alert" +
                "?name=${enc(senderName)}" +
                "&type=${enc(scamType.name)}" +
                "&conf=${(confidence * 100).toInt()}" +
                "&ts=${System.currentTimeMillis()}"
    }

    private fun ScamType.displayName(): String = when (this) {
        ScamType.UNKNOWN -> "Suspicious Call"
        ScamType.IRS_SCAM -> "IRS / Tax Scam"
        ScamType.TECH_SUPPORT -> "Tech Support Scam"
        ScamType.BANK_FRAUD -> "Bank Fraud"
        ScamType.LOTTERY_PRIZE -> "Lottery Scam"
        ScamType.SOCIAL_SECURITY -> "Social Security Scam"
        ScamType.ROBOCALL -> "Robocall"
        ScamType.PHISHING -> "Phishing Attempt"
        ScamType.INSURANCE -> "Insurance Scam"
        ScamType.INVESTMENT_SCAM -> "Investment Scam"
        ScamType.DONATION_FRAUD -> "Donation Fraud"
        ScamType.ROMANCE_SCAM -> "Romance / Pig-Butchering Scam"
        ScamType.DELIVERY_SCAM -> "Delivery / Package Scam"
        ScamType.JOB_SCAM -> "Job / Recruitment Scam"
    }
}
