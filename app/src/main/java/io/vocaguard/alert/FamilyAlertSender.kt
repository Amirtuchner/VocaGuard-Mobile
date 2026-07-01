package io.vocaguard.alert

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import io.vocaguard.BuildConfig
import io.vocaguard.data.FamilyContact
import io.vocaguard.data.FamilyGuardSettings
import io.vocaguard.data.ScamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Notifies family members / caregivers when a scam call is detected on the senior's device.
 *
 * Sends an SMS alert to every contact in [FamilyGuardSettings.contacts].
 * Requires the `SEND_SMS` permission and Family Guard Mode to be enabled.
 * Failures are logged but never crash the caller.
 */
class FamilyAlertSender(private val context: Context) {

    companion object {
        private const val TAG = "FamilyAlertSender"
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
            sendSms(contact, senderName, scamLabel, confidencePct, timeStr, customMessage)
        }
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private suspend fun sendSms(
        contact: FamilyContact,
        senderName: String,
        scamLabel: String,
        confidencePct: Int,
        timeStr: String,
        customMessage: String = ""
    ) = withContext(Dispatchers.IO) {
        try {
            val message = if (customMessage.isNotBlank()) {
                "[VocaGuard] $customMessage"
            } else {
                "[VocaGuard] SCAM ALERT\n$senderName's phone detected a $scamLabel at $timeStr ($confidencePct% confidence)."
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

    // ── Phone call alert (server-originated) ──────────────────────────────────

    /**
     * Asks the VocaGuard server to originate an outbound call to the primary family contact.
     * The server generates a TTS message and plays it when the caregiver answers —
     * so the audio is heard on the caregiver's device, not the senior's.
     *
     * Requires [FamilyGuardSettings.callAlertEnabled] = true.
     *
     * @param scamType  The type of scam that was detected.
     * @param confidence  Detection confidence (0.0–1.0).
     * @param delayBeforeCallMs  How long to wait before dialling (default 5 s).
     */
    suspend fun makeCallAlert(
        scamType: ScamType,
        confidence: Float,
        delayBeforeCallMs: Long = 5_000L,
        ignoreCallAlertEnabled: Boolean = false
    ) {
        if (!ignoreCallAlertEnabled && !settings.callAlertEnabled) return
        val contact = settings.contacts.firstOrNull() ?: return

        delay(delayBeforeCallMs)

        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("caregiver_number", contact.phoneNumber)
                    put("scam_type", scamType.displayName())
                    put("confidence_pct", (confidence * 100).toInt())
                    put("senior_name", settings.seniorName.ifBlank { "your family member" })
                    val seniorPhone = settings.seniorPhoneNumber
                    if (seniorPhone.isNotBlank()) put("senior_number", seniorPhone)
                }.toString()

                val body = payload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://${BuildConfig.TOKEN_SERVER_HOST}/call_caregiver")
                    .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
                    .post(body)
                    .build()

                buildTrustAllHttpClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Server call alert initiated to ${contact.name} (${contact.phoneNumber})")
                    } else {
                        Log.e(TAG, "Server call alert failed: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request server call alert", e)
            }
        }
    }

    private fun buildTrustAllHttpClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
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
        ScamType.SOCIAL_ENGINEERING -> "Social Engineering Scam"
    }
}
