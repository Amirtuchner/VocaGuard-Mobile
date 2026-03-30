package com.example.vocaguard.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.vocaguard.MainActivity
import com.example.vocaguard.R
import com.example.vocaguard.data.DetectionSettings
import com.example.vocaguard.data.ScamType
import com.example.vocaguard.receiver.BlockCallerReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class ScamAlertManager(private val context: Context) {

    companion object {
        private const val TAG = "ScamAlertManager"
        private const val ALERT_CHANNEL_ID = "scam_alert_channel"
        private const val ALERT_NOTIFICATION_ID = 2000
    }

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)
    private val alertScope = CoroutineScope(Dispatchers.IO + Job())

    init {
        createAlertNotificationChannel()
        initializeTextToSpeech()
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val localeTag = DetectionSettings.getInstance(context).locale
                val locale = Locale.forLanguageTag(localeTag)
                val result = textToSpeech?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS locale $localeTag not supported, falling back to en-US")
                    textToSpeech?.setLanguage(Locale.US)
                }
                ttsInitialized = true
                Log.d(TAG, "TTS initialized with locale $localeTag")
            } else {
                Log.e(TAG, "TTS initialization failed")
                ttsInitialized = false
            }
        }
    }

    private fun createAlertNotificationChannel() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Scam Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical alerts for detected scam calls"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            setShowBadge(true)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun triggerScamAlert(
        scamType: ScamType,
        transcript: String,
        confidence: Float,
        phoneNumber: String = ""
    ) {
        Log.w(TAG, "Triggering scam alert: $scamType (confidence: ${confidence * 100}%)")

        playWarningSound()
        vibrateWarning()
        speakAlert(scamType)
        showAlertNotification(scamType, transcript, confidence, phoneNumber)
    }

    private fun playWarningSound() {
        alertScope.launch {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                repeat(3) {
                    toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                    delay(400)
                }
                toneGen.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error playing warning sound", e)
            }
        }
    }

    private fun vibrateWarning() {
        try {
            if (vibrator?.hasVibrator() == true) {
                // Pattern: wait, vibrate, wait, vibrate, wait, vibrate
                val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating", e)
        }
    }

    private fun speakAlert(scamType: ScamType) {
        if (!ttsInitialized) {
            Log.w(TAG, "TTS not initialized, skipping speech alert")
            return
        }

        val alertMessage = when (scamType) {
            ScamType.IRS_SCAM -> "Warning! Potential IRS scam detected. The IRS never calls to demand immediate payment."
            ScamType.TECH_SUPPORT -> "Warning! Tech support scam detected. Legitimate companies don't call unsolicited about viruses."
            ScamType.BANK_FRAUD -> "Warning! Banking scam detected. Banks never ask for passwords or PINs over the phone."
            ScamType.LOTTERY_PRIZE -> "Warning! Lottery scam detected. You cannot win a lottery you didn't enter."
            ScamType.SOCIAL_SECURITY -> "Warning! Social Security scam detected. Your Social Security number cannot be suspended."
            ScamType.ROBOCALL -> "Warning! Robocall scam detected. This may be a fraudulent automated call."
            ScamType.PHISHING -> "Warning! Phishing attempt detected. Do not provide personal information."
            ScamType.INSURANCE -> "Warning! Insurance scam detected. Verify legitimacy before providing information."
            ScamType.INVESTMENT_SCAM -> "Warning! Investment scam detected. Guaranteed returns are a common fraud tactic."
            ScamType.DONATION_FRAUD -> "Warning! Donation scam detected. Research charities before donating."
            ScamType.UNKNOWN -> "Warning! Suspicious activity detected. Be cautious with this call."
        }

        try {
            textToSpeech?.speak(alertMessage, TextToSpeech.QUEUE_FLUSH, null, "scamAlert")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking alert", e)
        }
    }

    private fun showAlertNotification(
        scamType: ScamType,
        transcript: String,
        confidence: Float,
        phoneNumber: String
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("scam_type", scamType.name)
                putExtra("transcript", transcript)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = "⚠️ SCAM ALERT: ${formatScamType(scamType)}"
        val text = "Detected with ${(confidence * 100).toInt()}% confidence. Tap for details."

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$text\n\nRecent transcript: ${transcript.take(100)}…")
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setColor(0xFFFF0000.toInt())
            .addAction(R.drawable.ic_launcher_foreground, "View Details", openIntent)

        // Add "Block Caller" action if phone number is known
        if (phoneNumber.isNotBlank()) {
            val blockIntent = PendingIntent.getBroadcast(
                context, phoneNumber.hashCode(),
                Intent(BlockCallerReceiver.ACTION_BLOCK_CALLER).apply {
                    setClass(context, BlockCallerReceiver::class.java)
                    putExtra(BlockCallerReceiver.EXTRA_PHONE_NUMBER, phoneNumber)
                    putExtra(BlockCallerReceiver.EXTRA_SCAM_TYPE, scamType.name)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_launcher_foreground, "Block Caller", blockIntent)
        }

        notificationManager.notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private fun formatScamType(scamType: ScamType): String {
        return when (scamType) {
            ScamType.IRS_SCAM -> "IRS Scam"
            ScamType.TECH_SUPPORT -> "Tech Support Scam"
            ScamType.BANK_FRAUD -> "Bank Fraud"
            ScamType.LOTTERY_PRIZE -> "Lottery Scam"
            ScamType.SOCIAL_SECURITY -> "Social Security Scam"
            ScamType.ROBOCALL -> "Robocall Scam"
            ScamType.PHISHING -> "Phishing Attempt"
            ScamType.INSURANCE -> "Insurance Scam"
            ScamType.INVESTMENT_SCAM -> "Investment Scam"
            ScamType.DONATION_FRAUD -> "Donation Fraud"
            ScamType.UNKNOWN -> "Suspicious Call"
        }
    }

    fun shutdown() {
        alertScope.cancel()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}