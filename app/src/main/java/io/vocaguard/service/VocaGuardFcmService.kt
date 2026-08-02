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

        /** Set to true when every accept-call attempt failed — lets the in-call UI
         *  show the "Couldn't Connect" screen immediately instead of after the 35s timeout.
         *  Reset to false each time a new accept begins. */
        val acceptFailedFlow = MutableStateFlow(false)

        /** Emits the Asterisk channel of a call whose caller hung up before the user
         *  accepted (server call_cancelled push), OR whose accept was answered with
         *  410 call_gone. IncomingCallActivity collects this to stop ringing / bail
         *  out of "Connecting" instead of bridging into a dead call. */
        val callGoneFlow = MutableStateFlow<String?>(null)

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

                // The server's /accept-call is idempotent (token_server.py keeps an
                // _accepted_channels set — a duplicate request for an already-accepted
                // channel no-ops instead of firing a second AMI Originate, verified live
                // 2026-07-23). So retrying on ANY failure is safe and cannot double-ring.
                // A lost accept leaves the user staring at "Connecting" until the caller
                // gives up (happened 2026-07-28: accept never reached the server), so be
                // persistent: 4 attempts with short backoff, then tell the UI we failed.
                acceptFailedFlow.value = false
                val maxAttempts = 4
                for (attempt in 1..maxAttempts) {
                    try {
                        client.newCall(request).execute().use { response ->
                            Log.i(TAG, "Accept call signalled: ${response.code} (attempt $attempt)")
                            if (response.code == 410) {
                                // Caller hung up while we were still ringing — no
                                // bridge is coming, tell the UI the call is gone.
                                callGoneFlow.value = channel
                            }
                        }
                        return@launch
                    } catch (e: Exception) {
                        if (attempt == maxAttempts) {
                            Log.e(TAG, "Accept call failed after $maxAttempts attempts", e)
                            acceptFailedFlow.value = true
                        } else {
                            Log.w(TAG, "Accept call attempt $attempt failed, retrying", e)
                            kotlinx.coroutines.delay(attempt * 1000L)
                        }
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
            "call_cancelled" -> {
                // Caller hung up before the user accepted (gave up ringing, or the
                // dialplan's 60s window expired). Stop ringing NOW — without this the
                // app rings into the void and a late Accept bridges to a dead channel.
                val callerNumber    = message.data["caller_number"] ?: ""
                val asteriskChannel = message.data["asterisk_channel"] ?: ""
                Log.i(TAG, "Call cancelled by server: caller=$callerNumber channel=$asteriskChannel")
                // Dismiss the full-screen/heads-up incoming notification either way
                getSystemService(NotificationManager::class.java)
                    .cancel(io.vocaguard.ui.IncomingCallActivity.NOTIFICATION_ID)
                // Raise a "missed call" UNLESS the user deliberately declined — a
                // decline isn't a missed call. (An accepted call can't reach here:
                // the dialplan's h extension skips the cancel push when VG_ACCEPTED=1.)
                Log.i(TAG, "call_cancelled: userDeclined=${io.vocaguard.ui.IncomingCallActivity.userDeclined}")
                if (!io.vocaguard.ui.IncomingCallActivity.userDeclined) {
                    try {
                        showMissedCallNotification(callerNumber)
                    } catch (e: Exception) {
                        Log.e(TAG, "showMissedCallNotification failed", e)
                    }
                    // Record the miss in call history so it appears in the History tab.
                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                        TranscriptRepository.getInstance(applicationContext).save(
                            CallTranscript(
                                text = "",
                                detectedScamTypes = emptyList(),
                                phoneNumber = callerNumber,
                                direction = io.vocaguard.data.CallDirection.MISSED
                            )
                        )
                    }
                }
                callGoneFlow.value = asteriskChannel
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
            // Add to the local scam DB with a 7-day expiry. A non-zero expiresAt also
            // means this is NOT submitted to the community blocklist — automated
            // detections can be false positives (2026-07-27: a Hebrew job interview
            // was flagged BANK_FRAUD off STT garbage, permanently blacklisting a
            // legitimate number for every user). Only explicit user reports from the
            // History tab go to the community list.
            if (callerNumber.isNotEmpty()) {
                val scamDb = io.vocaguard.data.ScamDatabaseManager.getInstance(applicationContext)
                scamDb.reportScamNumber(
                    callerNumber, scamType,
                    expiresAt = System.currentTimeMillis() + io.vocaguard.data.ScamDatabaseManager.NETWORK_TTL_MS
                )
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
        // Fresh call — clear any decline flag left over from a previous call so a
        // genuine miss on THIS call still raises a missed-call notification.
        io.vocaguard.ui.IncomingCallActivity.userDeclined = false
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

    private fun showMissedCallNotification(callerNumber: String) {
        Log.i(TAG, "showMissedCallNotification called for $callerNumber")
        val channelId = "missed_call_channel_v2"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Missed Calls", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val displayNumber = if (callerNumber.isNotBlank()) callerNumber else "Unknown"
        // NOTE: id must NOT collide with ActiveCallReceiver.NOTIFICATION_ID (2002),
        // which the call-gone cleanup in IncomingCallActivity cancels — that was
        // silently wiping this notification the instant it posted (2026-08-02).
        nm.notify(2005, NotificationCompat.Builder(this, channelId)
            .setContentTitle("Missed call")
            .setContentText("From: $displayNumber")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build())
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
