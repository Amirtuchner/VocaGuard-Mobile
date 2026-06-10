package io.vocaguard.ml

import android.content.Context
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
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us"

    @Volatile private var cachedModel: Model? = null

    /**
     * Returns a loaded [Model], downloading it the first time (~40 MB).
     * Returns null if download or load fails — caller should degrade gracefully.
     */
    suspend fun getModel(context: Context): Model? = withContext(Dispatchers.IO) {
        cachedModel?.let { return@withContext it }

        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (!isReady(modelDir)) {
            Log.i(TAG, "Downloading Vosk model from $MODEL_URL")
            try {
                download(modelDir)
            } catch (e: Exception) {
                Log.e(TAG, "Vosk model download failed", e)
                modelDir.deleteRecursively()
                return@withContext null
            }
        }

        return@withContext try {
            Model(modelDir.absolutePath).also {
                cachedModel = it
                Log.i(TAG, "Vosk model loaded from ${modelDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk model load failed", e)
            null
        }
    }

    private fun isReady(dir: File): Boolean =
        dir.isDirectory && dir.list()?.isNotEmpty() == true

    private fun download(modelDir: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(Request.Builder().url(MODEL_URL).build()).execute()
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
