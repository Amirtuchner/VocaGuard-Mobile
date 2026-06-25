package io.vocaguard.service

import android.content.Context
import android.content.SharedPreferences
import io.vocaguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages user registration with the VocaGuard backend server.
 *
 * Registration flow:
 *  1. User enters their phone number in Settings
 *  2. [register] POSTs {phone_number, fcm_token} to /register
 *  3. Server creates a SIP extension, returns {sip_extension, sip_password, did_number}
 *  4. Credentials are stored in SharedPrefs and used by [VocaGuardSipManager]
 *  5. User dials *21*+[did_number]# to activate call forwarding
 */
object ServerDetectionManager {

    private const val PREFS_NAME      = "server_detection"
    private const val KEY_PHONE       = "phone_number"
    private const val KEY_SIP_EXT     = "sip_extension"
    private const val KEY_SIP_PASS    = "sip_password"
    private const val KEY_DID         = "did_number"
    private const val KEY_REGISTERED  = "registered"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isRegistered(): Boolean = prefs.getBoolean(KEY_REGISTERED, false)

    fun getPhoneNumber(): String  = prefs.getString(KEY_PHONE,   "") ?: ""
    fun getSipExtension(): String = prefs.getString(KEY_SIP_EXT, "") ?: ""
    fun getSipPassword(): String  = prefs.getString(KEY_SIP_PASS, "") ?: ""
    fun getDidNumber(): String    = prefs.getString(KEY_DID,     "") ?: ""

    /** Activation code the user dials to enable call forwarding. */
    fun getActivationCode(): String {
        val did = getDidNumber().trimStart('+')
        return if (did.isNotEmpty()) "*21*+$did#" else ""
    }

    data class RegistrationResult(
        val success: Boolean,
        val sipExtension: String = "",
        val sipPassword: String  = "",
        val didNumber: String    = "",
        val error: String        = "",
    )

    /**
     * Register (or re-register) with the backend server.
     * Stores SIP credentials in SharedPrefs on success.
     */
    suspend fun register(phoneNumber: String, fcmToken: String): RegistrationResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("phone_number", phoneNumber)
                    put("fcm_token", fcmToken)
                }.toString()

                val client = buildHttpClient()
                val request = Request.Builder()
                    .url("https://${BuildConfig.TOKEN_SERVER_HOST}/register")
                    .addHeader("Authorization", "Bearer ${BuildConfig.TOKEN_SERVER_SECRET}")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext RegistrationResult(
                        success = false,
                        error   = "Server returned ${response.code}"
                    )
                }

                val json       = JSONObject(response.body?.string() ?: "{}")
                val sipExt     = json.optString("sip_extension")
                val sipPass    = json.optString("sip_password")
                val didNumber  = json.optString("did_number")

                if (sipExt.isEmpty() || sipPass.isEmpty()) {
                    return@withContext RegistrationResult(
                        success = false, error = "Invalid server response"
                    )
                }

                prefs.edit()
                    .putString(KEY_PHONE,      phoneNumber)
                    .putString(KEY_SIP_EXT,    sipExt)
                    .putString(KEY_SIP_PASS,   sipPass)
                    .putString(KEY_DID,        didNumber)
                    .putBoolean(KEY_REGISTERED, true)
                    .apply()

                RegistrationResult(
                    success      = true,
                    sipExtension = sipExt,
                    sipPassword  = sipPass,
                    didNumber    = didNumber,
                )
            } catch (e: Exception) {
                RegistrationResult(success = false, error = e.message ?: "Unknown error")
            }
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // Self-signed cert trust (same pattern as VocaGuardFcmService)
    private fun buildHttpClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
