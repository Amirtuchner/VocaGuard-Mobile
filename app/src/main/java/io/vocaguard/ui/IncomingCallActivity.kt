package io.vocaguard.ui

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.media.AudioManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import io.vocaguard.service.VocaGuardSipManager
import androidx.core.app.NotificationCompat
import io.vocaguard.R
import io.vocaguard.data.CallTranscript
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamType
import io.vocaguard.data.TranscriptRepository
import io.vocaguard.receiver.ActiveCallReceiver
import io.vocaguard.service.ServerDetectionManager
import io.vocaguard.service.VocaGuardFcmService
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.vocaguard.service.ReEnableForwardingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class IncomingCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CALLER_NUMBER = "caller_number"
        const val EXTRA_CHANNEL       = "asterisk_channel"
        const val NOTIFICATION_ID     = 2001

        /** True while an IncomingCallActivity instance is alive — used to block duplicate launches. */
        @Volatile var isShowing = false
    }

    private var ringtone: Ringtone? = null
    private var activeChannel: String = ""
    private var callActive by mutableStateOf(false)
    private var activeCallerNumber: String = ""
    private var pendingCancel by mutableStateOf(false)
    private var volumeLevel by mutableStateOf(0f)
    private var showVolumeMeter by mutableStateOf(false)
    private var volumeHideJob: Job? = null
    private var isRecording by mutableStateOf(false)
    private var recordingPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Volume buttons control the call audio, not ringtone/media
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, null)

        isShowing = true

        val callerNumber    = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: ""
        val asteriskChannel = intent.getStringExtra(EXTRA_CHANNEL) ?: ""

        // Start looping ringtone
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(this, uri)?.also {
            it.isLooping = true
            it.play()
        }

        setContent {
            val sipState by VocaGuardSipManager.callState.collectAsState()
            val scamAlert by VocaGuardFcmService.scamAlertFlow.collectAsState()

            // When the remote side hangs up, clean up and finish.
            // If user cancelled during connecting, fire hangup once bridge is active.
            LaunchedEffect(sipState) {
                when {
                    sipState == VocaGuardSipManager.CallState.ACTIVE && pendingCancel -> {
                        // Wait for the Asterisk dialplan's Wait(1)+Bridge() to execute
                        // before sending BYE — otherwise Bridge() never runs and
                        // ORIG_CHANNEL is never cleaned up (scammer keeps ringing).
                        delay(2000)
                        hangUpAndFinish()
                    }
                    sipState == VocaGuardSipManager.CallState.ACTIVE && callActive && !pendingCancel -> {
                        // Bridge is established. Do NOT re-enable *21* here — doing so
                        // mid-call causes the carrier to re-forward the same call to
                        // Asterisk, triggering a duplicate incoming-call notification.
                        // Forwarding is re-enabled in onDestroy() after the call ends.
                    }
                    sipState == VocaGuardSipManager.CallState.ENDED && callActive -> {
                        getSystemService(NotificationManager::class.java)
                            .cancel(ActiveCallReceiver.NOTIFICATION_ID)
                        finish()
                    }
                }
            }

            if (callActive) {
                ActiveCallScreen(
                    callerNumber = activeCallerNumber,
                    connected = (sipState == VocaGuardSipManager.CallState.ACTIVE),
                    scamAlert = scamAlert,
                    volumeLevel = volumeLevel,
                    showVolumeMeter = showVolumeMeter,
                    isRecording = isRecording,
                    onToggleRecord = {
                        if (isRecording) {
                            VocaGuardSipManager.stopRecording()
                            isRecording = false
                        } else {
                            recordingPath = VocaGuardSipManager.startRecording(this@IncomingCallActivity)
                            isRecording = recordingPath != null
                        }
                    },
                    onEndCall = { hangUpAndFinish() },
                    onCancelConnecting = { pendingCancel = true }
                )
            } else {
                IncomingCallScreen(
                    callerNumber = callerNumber,
                    onAccept = {
                        ringtone?.stop()
                        ringtone = null
                        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                        activeChannel = asteriskChannel
                        activeCallerNumber = callerNumber
                        callActive = true
                        showActiveCallNotification(asteriskChannel, callerNumber)
                        // Tell the embedded SIP SDK to auto-answer the incoming INVITE,
                        // then trigger Asterisk to originate the call via AMI.
                        VocaGuardSipManager.acceptCall()
                        VocaGuardFcmService.acceptCall(asteriskChannel, callerNumber)
                    },
                    onDecline = { dismiss() }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (callActive && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val am = getSystemService(AudioManager::class.java)
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val current = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            volumeLevel = if (max > 0) current.toFloat() / max.toFloat() else 0f
            showVolumeMeter = true
            volumeHideJob?.cancel()
            volumeHideJob = lifecycleScope.launch {
                delay(2000)
                showVolumeMeter = false
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hangUpAndFinish() {
        if (isRecording) {
            VocaGuardSipManager.stopRecording()
            isRecording = false
        }
        VocaGuardSipManager.hangupCurrentCall()
        VocaGuardFcmService.hangupCall(activeChannel)  // AMI hangup as fallback
        getSystemService(NotificationManager::class.java).cancel(ActiveCallReceiver.NOTIFICATION_ID)
        finish()
    }

    private fun showActiveCallNotification(channel: String, callerNumber: String) {
        val channelId = "active_call_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Active Call", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val hangUpPi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ActiveCallReceiver.ACTION_HANG_UP, null, this, ActiveCallReceiver::class.java).apply {
                putExtra(ActiveCallReceiver.EXTRA_CHANNEL, channel)
                putExtra(ActiveCallReceiver.EXTRA_PHONE, ServerDetectionManager.getPhoneNumber() ?: "")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Tapping the notification body brings the user back to VocaGuard's active call screen
        val openVocaGuardPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, IncomingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val displayNumber = if (callerNumber.isNotBlank()) callerNumber else "Unknown"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VocaGuard: Monitoring Call")
            .setContentText("Tap to open • From: $displayNumber")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openVocaGuardPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", hangUpPi)
            .build()
        nm.notify(ActiveCallReceiver.NOTIFICATION_ID, notification)
    }

    private fun dismiss() {
        ringtone?.stop()
        ringtone = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        // Asterisk waits 60s then calls Answer() which deactivates *21*.
        // Schedule a re-enable 70s from now to cover that case.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ReEnableForwardingWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReEnableForwardingWorker>()
                .setInitialDelay(70, TimeUnit.SECONDS)
                .build()
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        isShowing = false
        // Save a clean-call record when the call ends, so "calls analyzed" counts every
        // server-monitored call. Skip if a scam record was already saved by VocaGuardFcmService.
        if (callActive && VocaGuardFcmService.scamAlertFlow.value == null) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                TranscriptRepository.getInstance(applicationContext).save(
                    CallTranscript(
                        text = "",
                        detectedScamTypes = emptyList(),
                        phoneNumber = activeCallerNumber
                    )
                )
            }
        }
        VocaGuardFcmService.scamAlertFlow.value = null
        // Re-enable forwarding aggressively: immediate + 3s + 6s + 10s
        // The carrier disables *21* when a call ends; the first attempt that
        // lands after the carrier finishes processing the BYE wins.
        reEnableForwardingSilently()
        for ((i, delaySec) in longArrayOf(3, 6, 10).withIndex()) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "re_enable_forwarding_$i",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReEnableForwardingWorker>()
                    .setInitialDelay(delaySec, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    private fun showReEnableForwardingNotification() {
        ServerDetectionManager.init(this)
        val code = ServerDetectionManager.getActivationCode()
        if (code.isEmpty()) return

        val callIntent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", code, null))
        val pi = PendingIntent.getActivity(
            this, 9001, callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nm = getSystemService(NotificationManager::class.java)
        val channelId = "forwarding_reminder"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Call Forwarding", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Re-enable call forwarding after each call" }
            )
        }
        nm.notify(3001, NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Re-enable Call Protection")
            .setContentText("Call forwarding was disabled. Tap to re-enable.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_launcher_foreground, "Re-enable Now", pi)
            .build()
        )
    }

    /**
     * Try to silently re-enable *21* forwarding using the USSD/MMI API (no user interaction).
     * Falls back to a tap-to-call notification if the modem rejects the request.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun reEnableForwardingSilently() {
        ServerDetectionManager.init(this)
        val code = ServerDetectionManager.getActivationCode()
        if (code.isEmpty()) return
        try {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java)
            tm.sendUssdRequest(code, object : android.telephony.TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    tm: android.telephony.TelephonyManager, request: String, response: CharSequence
                ) {
                    android.util.Log.i("VocaGuard", "Forwarding re-enabled silently: $response")
                }
                override fun onReceiveUssdResponseFailed(
                    tm: android.telephony.TelephonyManager, request: String, failureCode: Int
                ) {
                    android.util.Log.w("VocaGuard", "Silent re-enable failed ($failureCode), showing notification")
                    showReEnableForwardingNotification()
                }
            }, android.os.Handler(mainLooper))
        } catch (e: Exception) {
            android.util.Log.w("VocaGuard", "sendUssdRequest error: $e")
            showReEnableForwardingNotification()
        }
    }
}

@Composable
private fun ActiveCallScreen(
    callerNumber: String,
    connected: Boolean,
    scamAlert: Pair<ScamType, Float>?,
    volumeLevel: Float,
    showVolumeMeter: Boolean,
    isRecording: Boolean = false,
    onToggleRecord: () -> Unit = {},
    onEndCall: () -> Unit,
    onCancelConnecting: () -> Unit
) {
    val displayNumber = if (callerNumber.isNotBlank()) callerNumber else "Unknown"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Scam alert banner — shown prominently at the top when server detects a scam
        if (scamAlert != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFB71C1C))
                    .padding(vertical = 16.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "SCAM DETECTED",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "${scamAlert.first.name.replace('_', ' ')}  •  ${(scamAlert.second * 100).toInt()}% confidence",
                        color = Color(0xFFFFCDD2),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Hang up now!",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (scamAlert == null) 48.dp else 16.dp))
            if (connected) {
                Text("Call Active", color = Color(0xFF4CAF50), fontSize = 16.sp)
            } else {
                Text("Connecting…", color = Color(0xFFFFB300), fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(displayNumber, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("VocaGuard is monitoring this call", color = Color(0xFFAAAAAA), fontSize = 14.sp)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 64.dp)
        ) {
            if (connected && showVolumeMeter) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp)
                ) {
                    Text("Volume", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { volumeLevel },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFF333333)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (connected) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speaker button
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        var speakerOn by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                speakerOn = !speakerOn
                                VocaGuardSipManager.setSpeaker(speakerOn)
                            },
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (speakerOn) Color(0xFF1565C0) else Color(0xFF444444)
                            )
                        ) {
                            Text("\uD83D\uDD0A", fontSize = 22.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (speakerOn) "Speaker On" else "Speaker",
                            color = Color.White, fontSize = 12.sp
                        )
                    }
                    // Record button
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = onToggleRecord,
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF444444)
                            )
                        ) {
                            Text("\u23FA", fontSize = 22.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isRecording) "Recording" else "Record",
                            color = if (isRecording) Color(0xFFEF5350) else Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Button(
                onClick = if (connected) onEndCall else onCancelConnecting,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) Color(0xFFD32F2F) else Color(0xFF555555)
                )
            ) {
                Text("END", fontSize = 16.sp, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (connected) "End Call" else "Cancel",
                color = Color.White, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun IncomingCallScreen(
    callerNumber: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val displayNumber = if (callerNumber.isNotBlank()) callerNumber else "Unknown"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Incoming Call", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Text(displayNumber, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("VocaGuard is monitoring this call", color = Color(0xFFAAAAAA), fontSize = 14.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onDecline,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("X", fontSize = 24.sp, color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text("Decline", color = Color.White, fontSize = 12.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                ) {
                    Text("OK", fontSize = 20.sp, color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text("Accept", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
