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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class VocaGuardFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "VocaGuardFCM"

        /** Emits the latest scam alert so IncomingCallActivity can show an in-screen banner. */
        val scamAlertFlow = MutableStateFlow<Pair<io.vocaguard.data.ScamType, Float>?>(null)

        // Upload current device token to the Asterisk server over HTTPS.
        // Includes phone_number for multi-tenant routing if the user has registered.
        fun uploadToken(token: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val client = OkHttpClient()
                    val phoneNumber = ServerDetectionManager.getPhoneNumber()
                    val body = if (phoneNumber.isNotEmpty()) {
                        JSONObject().apply {
                            put("phone_number", phoneNumber)
                            put("fcm_token", token)
                        }.toString().toRequestBody("application/json".toMediaType())
                    } else {
                        token.toRequestBody("text/plain".toMediaType())
                    }
                    val request = Request.Builder()
                        .url("https://${BuildConfig.TOKEN_SERVER_HOST}/register-token")
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

        // Signal Asterisk to hang up the bridged call
        fun hangupCall(channel: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject().apply {
                        put("channel", channel)
                        val phone = ServerDetectionManager.getPhoneNumber()
                        if (phone.isNotEmpty()) put("phone_number", phone)
                    }.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("https://${BuildConfig.TOKEN_SERVER_HOST}/hangup")
                        .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
                        .post(body)
                        .build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        Log.i(TAG, "Hangup signalled: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to signal hangup", e)
                }
            }
        }

        // Signal Asterisk to bridge the waiting incoming call to the user's SIP phone.
        fun acceptCall(channel: String, callerNumber: String) {
            CoroutineScope(Dispatchers.IO).launch {
                val client = OkHttpClient.Builder()
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val jsonBody = JSONObject().apply {
                    put("channel", channel)
                    put("caller", callerNumber)
                    val phone = ServerDetectionManager.getPhoneNumber()
                    if (phone.isNotEmpty()) put("phone_number", phone)
                }.toString()
                val request = Request.Builder()
                    .url("https://${BuildConfig.TOKEN_SERVER_HOST}/accept-call")
                    .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                // A ConnectException means the TCP/TLS connection itself never came up,
                // so the request body was never sent — safe to retry once (a brief cellular
                // handoff or weak signal can blow past even a generous connect timeout).
                // Any other failure must NOT be retried: the server may already have
                // received the request and started the AMI Originate, and a second attempt
                // would send a duplicate SIP INVITE to Linphone, ringing the phone twice.
                for (attempt in 1..2) {
                    try {
                        client.newCall(request).execute().use { response ->
                            Log.i(TAG, "Accept call signalled: ${response.code}")
                        }
                        return@launch
                    } catch (e: java.net.ConnectException) {
                        if (attempt == 2) {
                            Log.e(TAG, "Accept call failed to connect after retry", e)
                        } else {
                            Log.w(TAG, "Accept call connect failed, retrying once", e)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to signal accept call", e)
                        return@launch
                    }
                }
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
                val callerNumber    = message.data["caller_number"] ?: ""
                val asteriskChannel = message.data["asterisk_channel"] ?: ""
                showIncomingCallNotification(callerNumber, asteriskChannel)
                return
            }
            "error_report" -> {
                showErrorReportNotification(
                    phone = message.data["phone"] ?: "",
                    error = message.data["error"] ?: "",
                    timestamp = message.data["timestamp"] ?: ""
                )
                return
            }
            "call_transcript" -> {
                val callerNumber = message.data["caller_number"] ?: ""
                val transcript   = message.data["transcript"] ?: ""
                if (callerNumber.isNotBlank() && transcript.isNotBlank()) {
                    Log.i(TAG, "Server transcript received for $callerNumber (${transcript.length} chars)")
                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                        TranscriptRepository.getInstance(applicationContext)
                            .updateRecentTranscript(callerNumber, transcript)
                    }
                }
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

        // Notify IncomingCallActivity to show an in-screen banner immediately
        scamAlertFlow.value = Pair(scamType, confidence)

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
            // Add to local scam DB and community blocklist
            if (callerNumber.isNotEmpty()) {
                val scamDb = io.vocaguard.data.ScamDatabaseManager.getInstance(applicationContext)
                scamDb.reportScamNumber(callerNumber, scamType)
            }
        }
    }

    private fun showIncomingCallNotification(callerNumber: String, asteriskChannel: String) {
        // Ignore duplicate FCM "incoming_call" messages while ANY call is already active
        // (SIP bridge call, or regular PSTN call like an outgoing call to a contact).
        val sipState = VocaGuardSipManager.callState.value
        val telecomInCall = try {
            val tm = getSystemService(android.telecom.TelecomManager::class.java)
            tm.isInCall
        } catch (_: SecurityException) { false }

        if (io.vocaguard.ui.IncomingCallActivity.isShowing ||
            sipState == VocaGuardSipManager.CallState.ACTIVE ||
            sipState == VocaGuardSipManager.CallState.INCOMING ||
            telecomInCall) {
            Log.i(TAG, "Ignoring incoming_call FCM — call already in progress (sip=$sipState, pstn=$telecomInCall, isShowing=${io.vocaguard.ui.IncomingCallActivity.isShowing})")
            return
        }
        // Reset stale ENDED state from previous call
        if (sipState == VocaGuardSipManager.CallState.ENDED) {
            VocaGuardSipManager.resetState()
        }
        // Set the flag here — before launching the activity — so any duplicate FCM that
        // arrives in the milliseconds before onCreate() fires is already blocked.
        io.vocaguard.ui.IncomingCallActivity.isShowing = true
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

        fun callIntent() = Intent(this, io.vocaguard.ui.IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(io.vocaguard.ui.IncomingCallActivity.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(io.vocaguard.ui.IncomingCallActivity.EXTRA_CHANNEL, asteriskChannel)
        }

        val fullScreenIntent = PendingIntent.getActivity(
            this, 0, callIntent(),
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

        // Also start the activity directly so the ringtone plays immediately,
        // regardless of whether Android fires the full-screen intent.
        // singleTop launch mode prevents a duplicate instance if the activity
        // is already on top (full-screen intent + startActivity → one instance).
        startActivity(callIntent())
        Log.i(TAG, "Incoming call screen launched for $displayNumber channel=$asteriskChannel")
    }

    private fun showErrorReportNotification(phone: String, error: String, timestamp: String) {
        val channelId = "error_report_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "Error Reports", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "User error reports from VocaGuard"
            }
            nm.createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Error: $phone")
            .setContentText(error)
            .setSubText(timestamp)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$error\n\nPhone: $phone\nTime: $timestamp"))
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
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
