package io.vocaguard.service

import android.content.Context
import android.util.Log
import io.vocaguard.BuildConfig
import io.vocaguard.data.DetectionSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fire-and-forget error reporter that sends user-facing errors to the
 * VocaGuard server, which then forwards them via email + FCM push.
 */
object ErrorReporter {

    private const val TAG = "ErrorReporter"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Report an error to the server in a background thread.
     * [context] is needed to read the registered phone number.
     * [error] is a short human-readable description of what failed.
     */
    fun report(context: Context, error: String) {
        val phone = ServerDetectionManager.getPhoneNumber()
        if (phone.isBlank()) return  // not registered — nothing to report

        Thread {
            try {
                val json = JSONObject().apply {
                    put("phone_number", phone)
                    put("error", error)
                }
                val request = Request.Builder()
                    .url("https://${BuildConfig.TOKEN_SERVER_HOST}/report-error")
                    .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
                    .post(json.toString().toRequestBody(JSON_TYPE))
                    .build()
                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Error reported: ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report error: ${e.message}")
            }
        }.start()
    }
}
