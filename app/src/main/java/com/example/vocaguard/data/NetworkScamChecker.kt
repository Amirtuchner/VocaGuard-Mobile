package com.example.vocaguard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks phone numbers against the NumVerify API (https://numverify.com).
 * Free tier: 250 requests/month. Set your API key via [apiKey].
 *
 * Results are cached locally for [CACHE_TTL_MS] (24 hours) to minimize API usage.
 */
class NetworkScamChecker private constructor(context: Context) {

    private var apiKey: String = ""

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        apiKey = key
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(): String = apiKey

    companion object {
        private const val TAG = "NetworkScamChecker"
        private const val PREFS_NAME = "vocaguard_network_scam_cache"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val ENDPOINT = "https://apilayer.net/api/validate"
        private const val KEY_API_KEY = "api_key"

        @Volatile
        private var instance: NetworkScamChecker? = null

        fun getInstance(context: Context): NetworkScamChecker {
            return instance ?: synchronized(this) {
                instance ?: NetworkScamChecker(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Checks the number remotely. Returns null if API key is unset, network is
     * unavailable, or the number is not found in the API response.
     */
    suspend fun checkNumber(phoneNumber: String): ScamInfo? = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            Log.d(TAG, "API key not set — skipping network check")
            return@withContext null
        }

        val cached = getCached(phoneNumber)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $phoneNumber")
            return@withContext cached
        }

        return@withContext try {
            val url = URL("$ENDPOINT?access_key=$apiKey&number=$phoneNumber&country_code=US&format=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "VocaGuard")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            try {
                val response = connection.inputStream.bufferedReader().readText()
                parseResponse(phoneNumber, response)?.also { result ->
                    putCached(phoneNumber, result)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network check failed for $phoneNumber: ${e.message}")
            null
        }
    }

    private fun parseResponse(phoneNumber: String, json: String): ScamInfo? {
        return try {
            val obj = JSONObject(json)

            if (!obj.optBoolean("valid", false)) {
                // Invalid number according to API — treat as suspicious
                return ScamInfo(phoneNumber = phoneNumber, isSuspicious = true)
            }

            val lineType = obj.optString("line_type", "")
            val carrier = obj.optString("carrier", "").lowercase()

            // Prepaid/VOIP lines are commonly used by scammers
            val isSuspicious = lineType in listOf("voip", "prepaid")
            val isKnownScammer = carrier.contains("scam") || carrier.contains("spam")

            if (isSuspicious || isKnownScammer) {
                ScamInfo(
                    phoneNumber = phoneNumber,
                    isSuspicious = isSuspicious,
                    isKnownScammer = isKnownScammer,
                    scamType = ScamType.ROBOCALL
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse API response: ${e.message}")
            null
        }
    }

    private fun getCached(phoneNumber: String): ScamInfo? {
        val json = prefs.getString(phoneNumber, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val timestamp = obj.getLong("timestamp")
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) {
                prefs.edit().remove(phoneNumber).apply()
                return null
            }
            ScamInfo(
                phoneNumber = phoneNumber,
                isKnownScammer = obj.getBoolean("isKnownScammer"),
                isSuspicious = obj.getBoolean("isSuspicious"),
                scamType = ScamType.valueOf(obj.getString("scamType"))
            )
        } catch (e: Exception) {
            // Corrupt entry — remove it so it doesn't accumulate
            prefs.edit().remove(phoneNumber).apply()
            null
        }
    }

    private fun putCached(phoneNumber: String, info: ScamInfo) {
        val obj = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("isKnownScammer", info.isKnownScammer)
            put("isSuspicious", info.isSuspicious)
            put("scamType", info.scamType.name)
        }
        prefs.edit().putString(phoneNumber, obj.toString()).apply()
    }

    fun clearCache() {
        // Only remove cached lookups, preserve the stored API key
        val key = prefs.getString(KEY_API_KEY, "")
        prefs.edit().clear().apply()
        if (!key.isNullOrEmpty()) {
            prefs.edit().putString(KEY_API_KEY, key).apply()
        }
    }
}