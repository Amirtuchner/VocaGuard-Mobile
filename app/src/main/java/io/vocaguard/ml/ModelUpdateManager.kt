package io.vocaguard.ml

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks a remote version manifest and downloads an updated TFLite model if one is available.
 *
 * The version manifest is a JSON object served from [VERSION_URL]:
 * ```json
 * { "version": "1.1", "url": "https://example.com/models/scam_detector_v1.1.tflite" }
 * ```
 *
 * Downloaded models are written to [modelFile], which [TFLiteScamClassifier] checks before
 * falling back to the bundled asset. The current model version is persisted in SharedPrefs so
 * subsequent launches skip re-downloading when the model is already up to date.
 */
class ModelUpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ModelUpdateManager"
        private const val PREFS_NAME = "vocaguard_model_update"
        private const val KEY_CURRENT_VERSION = "model_version"
        private const val BUNDLED_VERSION = "1.0"

        /**
         * URL of the version manifest JSON. Replace with your own endpoint before shipping.
         * The URL must serve HTTPS and return Content-Type: application/json.
         */
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/Amirtuchner/VocaGuard-Mobile/main/models/version.json"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val KEY_LAST_CHECK_TS = "last_model_check_ts"
        /** Auto-check at most once every 24 h to avoid unnecessary network calls. */
        private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

        @Volatile private var instance: ModelUpdateManager? = null

        fun getInstance(context: Context): ModelUpdateManager =
            instance ?: synchronized(this) {
                instance ?: ModelUpdateManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Path where the downloaded model is stored. */
    val modelFile: File = File(context.filesDir, "models/scam_detector.tflite")

    val currentVersion: String
        get() = prefs.getString(KEY_CURRENT_VERSION, BUNDLED_VERSION) ?: BUNDLED_VERSION

    /**
     * Checks the remote version manifest and downloads the model if a newer version is available.
     *
     * @return A human-readable status string suitable for display in the Settings UI.
     */
    suspend fun checkAndDownload(): String = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_LAST_CHECK_TS, System.currentTimeMillis()).apply()
        try {
            val manifest = fetchManifest()
            val remoteVersion = manifest.getString("version")
            val remoteUrl = manifest.getString("url")
            val expectedSha256 = manifest.optString("sha256", "")

            if (remoteVersion == currentVersion) {
                Log.i(TAG, "Model is up to date (v$currentVersion)")
                return@withContext "Model is up to date (v$currentVersion)"
            }

            Log.i(TAG, "Downloading model v$remoteVersion from $remoteUrl")
            downloadModel(remoteUrl, expectedSha256)

            prefs.edit().putString(KEY_CURRENT_VERSION, remoteVersion).apply()
            Log.i(TAG, "Model updated to v$remoteVersion")
            "Model updated to v$remoteVersion — restart the app to apply"
        } catch (e: Exception) {
            Log.e(TAG, "Model update check failed", e)
            "Update check failed: ${e.message}"
        }
    }

    /**
     * Returns true if an auto-check is due (last check was more than [AUTO_CHECK_INTERVAL_MS] ago).
     * Intended for use in the Settings ViewModel to trigger a background check on startup.
     */
    fun isAutoCheckDue(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK_TS, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    private fun fetchManifest(): JSONObject {
        val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode} from version manifest")
            }
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadModel(urlString: String, expectedSha256: String = "") {
        modelFile.parentFile?.mkdirs()
        val tmp = File(modelFile.parent, "${modelFile.name}.tmp")

        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode} downloading model")
            }
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Verify SHA-256 if the manifest provided one — prevents model poisoning.
            if (expectedSha256.isNotEmpty()) {
                val actual = sha256Hex(tmp)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    throw Exception(
                        "Model hash mismatch (expected $expectedSha256, got $actual). " +
                        "Download may be corrupted or tampered."
                    )
                }
                Log.i(TAG, "Model SHA-256 verified: $actual")
            }
            // Atomic rename: only replace the live model once download and verification complete.
            if (!tmp.renameTo(modelFile)) {
                tmp.delete()
                throw Exception("Failed to install downloaded model")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
            connection.disconnect()
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
