package io.vocaguard.data

import android.content.Context
import android.util.Log
import io.vocaguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Submits user-reported scam numbers to a community aggregation endpoint.
 *
 * The endpoint is configurable via [DetectionSettings.reportEndpointUrl]. When that field is
 * empty, submission is a no-op so the feature is opt-in by default.
 *
 * Expected server contract (POST application/json):
 * ```json
 * { "number": "15551234567", "type": "IRS_SCAM", "appVersion": "1.1" }
 * ```
 * The server should respond with HTTP 200–299 on success. Any other status is treated as a
 * transient failure (the report is silently dropped — no retry queue to avoid persisting PII).
 *
 * Privacy note: only the normalised E.164 number and scam type are sent. No transcript,
 * device identifiers, or user account details are included.
 */
class ReportSubmitter private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ReportSubmitter"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000

        @Volatile private var instance: ReportSubmitter? = null

        fun getInstance(context: Context): ReportSubmitter =
            instance ?: synchronized(this) {
                instance ?: ReportSubmitter(context.applicationContext).also { instance = it }
            }
    }

    private val settings = DetectionSettings.getInstance(context)

    /**
     * Submits [phoneNumber] and [scamType] to the configured endpoint if one is set.
     * This is a suspend function and must be called from a coroutine; it returns immediately
     * (success silently, failure logged) so callers are never blocked on network I/O.
     */
    suspend fun submit(phoneNumber: String, scamType: ScamType) = withContext(Dispatchers.IO) {
        val endpoint = settings.reportEndpointUrl.trim()
        if (endpoint.isEmpty()) return@withContext  // feature disabled

        if (!endpoint.startsWith("https://")) {
            Log.w(TAG, "Report endpoint must use HTTPS. Skipping submission.")
            return@withContext
        }

        try {
            val payload = JSONObject().apply {
                put("number", phoneNumber)
                put("type", scamType.name)
                put("appVersion", BuildConfig.VERSION_NAME)
            }.toString()

            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")

            try {
                connection.outputStream.bufferedWriter().use { it.write(payload) }
                val code = connection.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "Report submitted for $phoneNumber (HTTP $code)")
                } else {
                    Log.w(TAG, "Report submission returned HTTP $code for $phoneNumber")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Network errors are non-fatal — the local DB already has the report.
            Log.e(TAG, "Failed to submit report for $phoneNumber", e)
        }
    }
}
