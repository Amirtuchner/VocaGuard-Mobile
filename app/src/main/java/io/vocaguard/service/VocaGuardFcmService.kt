package io.vocaguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.vocaguard.BuildConfig
import io.vocaguard.MainActivity
import io.vocaguard.R
import io.vocaguard.alert.ScamAlertManager
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.ScamType
import io.vocaguard.data.TranscriptRepository
import io.vocaguard.ui.ScamOverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class VocaGuardFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "VocaGuardFCM"

        // Upload current device token to the Asterisk server over HTTPS
        fun uploadToken(token: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val client = OkHttpClient()
                    val body = token.toRequestBody("text/plain".toMediaType())
                    val request = Request.Builder()
                        .url("https://178.105.164.91:8080/register-token")
                        .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
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

        when (message.data["type"]) {
            "incoming_call" -> {
                val callerNumber = message.data["caller_number"] ?: ""
                showIncomingCallNotification(callerNumber)
                return
            }
            "scam_alert" -> { /* handled below */ }
            else -> return
        }

        val keywords = message.data["keywords"] ?: "unknown"
        val transcript = message.data["transcript"] ?: ""
        val callerNumber = message.data["caller_number"] ?: ""
        val confidence = message.data["confidence"]?.toFloatOrNull() ?: 0.9f
        // Server v2 sends scam_type directly; fall back to keyword inference for v1 payloads
        val scamType = message.data["scam_type"]
            ?.let { runCatching { ScamType.valueOf(it) }.getOrNull() }
            ?: inferScamType(keywords)

        Log.w(TAG, "Scam alert from server! Keywords: $keywords, caller: $callerNumber, confidence: $confidence")

        // Notification + TTS + vibration
        val alertManager = ScamAlertManager(applicationContext)
        alertManager.triggerScamAlert(
            scamType = scamType,
            transcript = transcript,
            confidence = confidence,
            phoneNumber = callerNumber
        )

        // In-call overlay (requires SYSTEM_ALERT_WINDOW)
        val overlayManager = ScamOverlayManager(applicationContext)
        overlayManager.show(scamType, confidence)

        // Persist to history so the History tab shows server-detected scams
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            TranscriptRepository.getInstance(applicationContext).save(
                CallTranscript(
                    text = "[Server] $transcript",
                    detectedScamTypes = listOf(scamType.name),
                    phoneNumber = callerNumber
                )
            )
        }
    }

    private fun showIncomingCallNotification(callerNumber: String) {
        val channelId = "incoming_call_channel"
        val nm = getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "Incoming Call", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alert when a monitored call arrives"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 300, 500)
            }
            nm.createNotificationChannel(channel)
        }

        val fullScreenIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, io.vocaguard.ui.IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(io.vocaguard.ui.IncomingCallActivity.EXTRA_CALLER_NUMBER, callerNumber)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayNumber = if (callerNumber.isNotBlank()) callerNumber else "Unknown"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Call")
            .setContentText("From: $displayNumber")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .setAutoCancel(true)
            .build()

        nm.notify(io.vocaguard.ui.IncomingCallActivity.NOTIFICATION_ID, notification)

        // Also launch the activity directly so it shows immediately
        startActivity(Intent(this, io.vocaguard.ui.IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(io.vocaguard.ui.IncomingCallActivity.EXTRA_CALLER_NUMBER, callerNumber)
        })

        Log.i(TAG, "Incoming call screen launched for $displayNumber")
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
