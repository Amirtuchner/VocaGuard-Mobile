package io.vocaguard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a community-maintained JSON blocklist and imports the numbers into
 * [ScamDatabaseManager]. Results are cached for 24 hours so users can trigger
 * a manual sync without hammering the remote server on every launch.
 *
 * Expected JSON format (array of objects):
 * ```json
 * [
 *   { "number": "15551234567", "type": "IRS_SCAM" },
 *   { "number": "15559876543", "type": "ROBOCALL" }
 * ]
 * ```
 * The "type" field must match a [ScamType] enum name; unknown values default to [ScamType.UNKNOWN].
 */
class CommunityScamSync private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "CommunityScamSync"
        private const val PREFS_NAME = "vocaguard_community_sync"
        private const val KEY_SYNC_URL = "sync_url"
        private const val KEY_LAST_SYNC = "last_sync_ms"
        private const val KEY_LAST_ERROR = "last_sync_error"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000  // 24 hours
        private const val DEFAULT_SYNC_URL =
            "https://gist.githubusercontent.com/Amirtuchner/9afad64b4504a9a4a753859a47fc2e9d/raw/0d51cb2e90fbb3a680b12726f0a81bed0e7c0525/blocklist.json"
        /**
         * Fallback URL tried when [DEFAULT_SYNC_URL] is unreachable. Replace with your
         * own CDN-hosted copy of the blocklist for production deployments.
         */
        private const val FALLBACK_SYNC_URL =
            "https://raw.githubusercontent.com/Amirtuchner/VocaGuard-Mobile/main/blocklist.json"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        @Volatile
        private var instance: CommunityScamSync? = null

        fun getInstance(context: Context): CommunityScamSync =
            instance ?: synchronized(this) {
                instance ?: CommunityScamSync(context.applicationContext).also { instance = it }
            }

        /** For use in tests only — forces the next [getInstance] call to create a fresh instance. */
        fun resetInstance() {
            instance = null
        }
    }

    /** Configurable blocklist URL; persisted across restarts. */
    var syncUrl: String
        get() = prefs.getString(KEY_SYNC_URL, DEFAULT_SYNC_URL) ?: DEFAULT_SYNC_URL
        set(value) = prefs.edit().putString(KEY_SYNC_URL, value).apply()

    /** Epoch milliseconds of the last successful sync, or 0 if never synced. */
    val lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    /** True when the cached data is still fresh (less than 24 h old). */
    val isCacheFresh: Boolean
        get() = System.currentTimeMillis() - lastSyncMs < CACHE_TTL_MS

    /** Human-readable description of the most recent sync error, or empty if last sync succeeded. */
    val lastSyncError: String
        get() = prefs.getString(KEY_LAST_ERROR, "") ?: ""

    /**
     * Fetches the blocklist and imports numbers into [ScamDatabaseManager].
     *
     * Tries the primary [syncUrl] first; if all attempts fail, automatically retries against
     * [FALLBACK_SYNC_URL] before giving up. The last error is stored and exposed via
     * [lastSyncError] so the UI can display a meaningful message.
     *
     * @param force Skip the 24-hour cache check and always fetch.
     * @return Number of numbers imported, or -1 on error.
     */
    suspend fun sync(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (!force && isCacheFresh) {
            Log.d(TAG, "Cache still fresh, skipping sync (last sync ${lastSyncMs})")
            return@withContext 0
        }

        val urlsToTry = listOf(syncUrl, FALLBACK_SYNC_URL).distinct()
        var lastError = ""

        for (url in urlsToTry) {
            Log.i(TAG, "Starting community sync from $url")
            // Retry up to 3 times per URL with exponential backoff (1 s, 2 s, 4 s).
            repeat(3) { attempt ->
                try {
                    val json = fetchJson(url)
                    val count = parseAndImport(json)
                    prefs.edit()
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .putString(KEY_LAST_ERROR, "")
                        .apply()
                    Log.i(TAG, "Community sync complete: $count numbers imported from $url")
                    return@withContext count
                } catch (e: java.io.IOException) {
                    lastError = "Network error: ${e.message}"
                    Log.w(TAG, "Network error on attempt ${attempt + 1} ($url): ${e.message}")
                    if (attempt < 2) delay(1_000L shl attempt) // 1 s, 2 s
                } catch (e: Exception) {
                    lastError = "Parse error: ${e.message}"
                    Log.e(TAG, "Non-retriable sync error from $url", e)
                    return@repeat // Skip remaining retries for this URL, try fallback
                }
            }
        }

        Log.e(TAG, "All sync attempts failed. Last error: $lastError")
        prefs.edit().putString(KEY_LAST_ERROR, lastError).apply()
        -1
    }

    private fun fetchJson(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun parseAndImport(json: String): Int {
        val manager = ScamDatabaseManager.getInstance(appContext)
        val array = JSONArray(json)
        var count = 0
        val communityExpiresAt = System.currentTimeMillis() + ScamDatabaseManager.COMMUNITY_TTL_MS
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val number = obj.optString("number", "").trim()
            val typeName = obj.optString("type", "UNKNOWN").trim()
            if (number.isBlank()) continue
            val scamType = try {
                ScamType.valueOf(typeName)
            } catch (_: IllegalArgumentException) {
                ScamType.UNKNOWN
            }
            // Community numbers expire after 30 days so stale entries self-clean.
            manager.reportScamNumber(number, scamType, expiresAt = communityExpiresAt)
            count++
        }
        return count
    }
}
