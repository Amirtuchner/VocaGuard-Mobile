package io.vocaguard.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.vocaguard.BuildConfig
import io.vocaguard.MainActivity
import io.vocaguard.service.ServerDetectionManager
import io.vocaguard.service.VocaGuardSipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ActiveCallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_HANG_UP    = "io.vocaguard.ACTION_HANG_UP"
        const val EXTRA_CHANNEL     = "active_call_channel"
        const val EXTRA_PHONE       = "active_call_phone"
        const val NOTIFICATION_ID   = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HANG_UP) return
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return

        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

        // Hang up via SDK immediately (synchronous, on main thread)
        VocaGuardSipManager.hangupCurrentCall()

        // Bring VocaGuard to foreground — must be synchronous while in notification-tap window
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )

        // goAsync() keeps the receiver process alive while the HTTP hangup request completes.
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: ServerDetectionManager.getPhoneNumber()
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("channel", channel)
                    if (!phone.isNullOrBlank()) put("phone_number", phone)
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://${BuildConfig.TOKEN_SERVER_HOST}/hangup")
                    .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
                    .post(body)
                    .build()
                OkHttpClient().newCall(request).execute().use { response ->
                    Log.i("ActiveCallReceiver", "Hangup response: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("ActiveCallReceiver", "Hangup request failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
