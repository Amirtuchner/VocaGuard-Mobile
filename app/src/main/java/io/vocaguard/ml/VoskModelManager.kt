package io.vocaguard.ml

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object VoskModelManager {
    private const val TAG = "VoskModelManager"

    // English model (default)
    private const val MODEL_URL_EN      = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR_NAME_EN = "vosk-model-small-en-us"

    // Hebrew model — used for Israeli SIM cards (Hot Mobile and other IL carriers)
    private const val MODEL_URL_HE      = "https://alphacephei.com/vosk/models/vosk-model-small-he.zip"
    private const val MODEL_DIR_NAME_HE = "vosk-model-small-he"

    @Volatile private var cachedModel: Model? = null
    @Volatile private var cachedModelDir: String? = null

    /**
     * Returns a loaded [Model] appropriate for the device's SIM country.
     * Israeli SIM → Hebrew model; all others → English model.
     * Downloads the model on first use (~40–50 MB). Returns null on failure.
     */
    suspend fun getModel(context: Context): Model? = withContext(Dispatchers.IO) {
        val tm         = context.getSystemService(TelephonyManager::class.java)
        val simCountry = tm?.simCountryIso?.lowercase() ?: ""
        val isIsraeli  = simCountry == "il"
        val modelUrl     = if (isIsraeli) MODEL_URL_HE      else MODEL_URL_EN
        val modelDirName = if (isIsraeli) MODEL_DIR_NAME_HE else MODEL_DIR_NAME_EN

        // Return cached model only if it is the same language
        if (cachedModel != null && cachedModelDir == modelDirName) {
            return@withContext cachedModel
        }

        val modelDir = File(context.filesDir, modelDirName)
        if (!isReady(modelDir)) {
            Log.i(TAG, "Downloading Vosk model ($modelDirName) from $modelUrl")
            try {
                download(modelUrl, modelDir)
            } catch (e: Exception) {
                Log.e(TAG, "Vosk model download failed", e)
                modelDir.deleteRecursively()
                return@withContext null
            }
        }

        return@withContext try {
            Model(modelDir.absolutePath).also {
                cachedModel    = it
                cachedModelDir = modelDirName
                Log.i(TAG, "Vosk model loaded: $modelDirName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk model load failed", e)
            null
        }
    }

    private fun isReady(dir: File): Boolean =
        dir.isDirectory && dir.list()?.isNotEmpty() == true

    private fun download(url: String, modelDir: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        modelDir.mkdirs()
        ZipInputStream(response.body!!.byteStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Strip the top-level zip directory (e.g. "vosk-model-small-en-us-0.15/")
                val rel = entry.name.substringAfter('/')
                if (rel.isNotEmpty()) {
                    val dest = File(modelDir, rel)
                    if (entry.isDirectory) dest.mkdirs()
                    else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.i(TAG, "Vosk model extracted to ${modelDir.absolutePath}")
    }
}
