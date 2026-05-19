package io.vocaguard.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.vocaguard.alert.ScamAlertManager
import io.vocaguard.data.ScamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class VocaGuardFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "VocaGuardFCM"

        // Upload current device token to the Asterisk server
        fun uploadToken(token: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val client = OkHttpClient()
                    val body = token.toRequestBody("text/plain".toMediaType())
                    val request = Request.Builder()
                        .url("http://178.105.164.91:8080/register-token")
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { response ->
                        Log.i(TAG, "Token uploaded: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload FCM token", e)
                }
            }
        }

        fun refreshToken() {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                Log.i(TAG, "FCM token: $token")
                uploadToken(token)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token: $token")
        uploadToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "FCM message received: ${message.data}")

        if (message.data["type"] != "scam_alert") return

        val keywords = message.data["keywords"] ?: "unknown"
        val transcript = message.data["transcript"] ?: ""

        Log.w(TAG, "Scam alert from server! Keywords: $keywords")

        // Trigger the existing alert system
        val alertManager = ScamAlertManager(applicationContext)
        alertManager.triggerScamAlert(
            scamType = inferScamType(keywords),
            transcript = transcript,
            confidence = 0.9f,
            phoneNumber = ""
        )
    }

    private fun inferScamType(keywords: String): ScamType {
        val k = keywords.lowercase()
        return when {
            k.contains("irs") || k.contains("tax") || k.contains("arrest") -> ScamType.IRS_SCAM
            k.contains("virus") || k.contains("microsoft") || k.contains("computer") -> ScamType.TECH_SUPPORT
            k.contains("bank") || k.contains("account") || k.contains("transfer") -> ScamType.BANK_FRAUD
            k.contains("won") || k.contains("lottery") || k.contains("prize") -> ScamType.LOTTERY_PRIZE
            k.contains("social security") || k.contains("ssn") -> ScamType.SOCIAL_SECURITY
            k.contains("press") || k.contains("do not hang") -> ScamType.ROBOCALL
            k.contains("password") || k.contains("verify") -> ScamType.PHISHING
            k.contains("love") || k.contains("bail") || k.contains("grandma") -> ScamType.ROMANCE_SCAM
            k.contains("package") || k.contains("customs") || k.contains("fedex") -> ScamType.DELIVERY_SCAM
            k.contains("job") || k.contains("hiring") || k.contains("work from home") -> ScamType.JOB_SCAM
            k.contains("donate") || k.contains("donation") -> ScamType.DONATION_FRAUD
            else -> ScamType.UNKNOWN
        }
    }
}
