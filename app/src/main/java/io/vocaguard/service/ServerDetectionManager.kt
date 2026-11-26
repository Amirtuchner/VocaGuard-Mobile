package io.vocaguard.service

import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Manages user registration with the VocaGuard backend server.
 *
 * Registration flow:
 *  1. The number is proven: the verification call ([startNumberVerification]; the
 *     server pushes back the number plus a "cli" ticket) or an SMS code
 *     ([requestSmsCode] / [checkSmsCode] → "sms" ticket).
 *  2. [register] POSTs {phone_number, fcm_token, ticket} to /register
 *  3. Server creates a SIP extension, returns {sip_extension, sip_password, did_number}
 *     plus this device's auth token, which every later request sends ([ServerApi]).
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
    private const val KEY_TRIAL_START = "trial_start_epoch"  // server-authoritative, reinstall-proof
    private const val KEY_AUTH_TOKEN  = "auth_token"         // per-device server auth (file excluded from backup)

    /** /register error: this number already has an account; prove it with an SMS code. */
    const val ERR_SMS_REQUIRED = "sms_required"
    /** /register error: a new number needs a verification ticket first. */
    const val ERR_VERIFICATION_REQUIRED = "verification_required"

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

    /** This device's server auth token ("" before the first verified registration,
     *  or if [init] hasn't run yet in this process). */
    fun getAuthTokenOrEmpty(): String =
        if (::prefs.isInitialized) prefs.getString(KEY_AUTH_TOKEN, "") ?: "" else ""

    /** Unix epoch (seconds) when this user's free trial started (first server
     *  registration), per the server. 0 = never registered. Reinstall-proof: the
     *  server returns the original start, so it can't be reset by reinstalling. */
    fun getTrialStartEpoch(): Long = prefs.getLong(KEY_TRIAL_START, 0L)

    /**
     * Activation code the user dials to enable call forwarding.
     * Verizon uses *72+number (no # suffix).
     * All other carriers use *21*+number#.
     */
    fun getActivationCode(context: Context? = null): String {
        val did = getDidNumber().trimStart('+')
        if (did.isEmpty()) return ""
        if (context != null) {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val operatorName = tm?.networkOperatorName?.lowercase() ?: ""
            val isVerizon    = operatorName.contains("verizon")
            if (isVerizon)   return "*72+$did"
        }
        return "*21*+$did#"
    }

    /**
     * Code the user dials to cancel call forwarding (e.g. before uninstalling).
     * Verizon uses *73 (no suffix).
     * All other carriers use the GSM MMI ##21#, which deactivates "forward all
     * calls" AND erases the registered number — more thorough than #21#, which
     * leaves the number registered at the carrier.
     */
    fun getDeactivationCode(context: Context? = null): String {
        if (context != null) {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val operatorName = tm?.networkOperatorName?.lowercase() ?: ""
            if (operatorName.contains("verizon")) return "*73"
        }
        return "##21#"
    }

    data class RegistrationResult(
        val success: Boolean,
        val sipExtension: String = "",
        val sipPassword: String  = "",
        val didNumber: String    = "",
        val error: String        = "",
        /** Server's reason on a 401: [ERR_SMS_REQUIRED] / [ERR_VERIFICATION_REQUIRED]. */
        val errorCode: String    = "",
    )

    /**
     * Register (or re-register) with the backend server.
     * Stores SIP credentials (and the auth token, when the server issues one) on success.
     *
     * [ticket] proves the number (verification call or SMS code). Re-registering an
     * already-registered device needs none: its stored auth token is sent instead.
     */
    suspend fun register(phoneNumber: String, fcmToken: String, ticket: String = ""): RegistrationResult =
        withContext(Dispatchers.IO) {
            try {
                // Our token belongs to the number we registered; never send it for another.
                // (Compare the subscriber digits: "+972 52…" and "052…" are the same number.)
                val core = { n: String -> n.filter(Char::isDigit).takeLast(9) }
                if (getPhoneNumber().isNotEmpty() && core(getPhoneNumber()) != core(phoneNumber)) {
                    prefs.edit().remove(KEY_AUTH_TOKEN).commit()
                }
                val body = JSONObject().apply {
                    put("phone_number", phoneNumber)
                    put("fcm_token", fcmToken)
                    // This build connects over TLS and supports SRTP: the server then
                    // switches this user's endpoint to strict SRTP (RTP/SAVP offers).
                    // Older builds omit it and keep optimistic (plain-audio) offers.
                    put("srtp", true)
                    // Per-user auth: "this build understands auth tokens" (so the server
                    // doesn't treat it as a legacy app), plus the proof of the number.
                    put("auth_v", 1)
                    if (ticket.isNotEmpty()) put("ticket", ticket)
                }.toString()

                val request = ServerApi.request("/register")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val (code, text) = ServerApi.client.newCall(request).execute().use { r ->
                    r.code to (r.body?.string() ?: "{}")
                }
                if (code !in 200..299) {
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                    return@withContext RegistrationResult(
                        success   = false,
                        error     = "Server returned $code",
                        errorCode = if (code == 401) reason else "",
                    )
                }

                val json       = JSONObject(text)
                val sipExt     = json.optString("sip_extension")
                val sipPass    = json.optString("sip_password")
                val didNumber  = json.optString("did_number")
                val trialStart = json.optLong("trial_start_epoch", 0L)

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
                val authToken = json.optString("auth_token")
                if (authToken.isNotEmpty()) prefs.edit().putString(KEY_AUTH_TOKEN, authToken).apply()
                // Store the server's trial start (only if provided) so the paywall
                // logic knows when the free month began. Never overwrite a good
                // value with 0 (older server / missing field).
                if (trialStart > 0L) {
                    prefs.edit().putLong(KEY_TRIAL_START, trialStart).apply()
                }

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

    data class VerifyStartResult(
        val success: Boolean,
        val verifyNumber: String = "",
        val code: String = "",
        val error: String = "",
    )

    /**
     * Start CLI-capture number detection. Sends our FCM token to the server, which
     * returns a DID to call plus a short numeric nonce. The caller then places a
     * brief call to [VerifyStartResult.verifyNumber] sending [VerifyStartResult.code]
     * as DTMF; the server reads the call's ANI (the device's real number) and pushes
     * it back via FCM ("number_verified"). No phone-number permission or typing needed.
     */
    suspend fun startNumberVerification(fcmToken: String): VerifyStartResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("fcm_token", fcmToken) }.toString()
                val request = ServerApi.request("/verify-number/start")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val (status, text) = ServerApi.client.newCall(request).execute().use { r ->
                    r.code to (r.body?.string() ?: "{}")
                }
                if (status !in 200..299) {
                    return@withContext VerifyStartResult(false, error = "Server returned $status")
                }
                val json = JSONObject(text)
                val num  = json.optString("verify_number")
                val code = json.optString("code")
                if (num.isEmpty() || code.isEmpty()) {
                    return@withContext VerifyStartResult(false, error = "Invalid server response")
                }
                VerifyStartResult(true, verifyNumber = num, code = code)
            } catch (e: Exception) {
                VerifyStartResult(false, error = e.message ?: "Unknown error")
            }
        }

    /** Outcome of an SMS-code request or check. */
    enum class SmsStatus { SENT, OK, WRONG, EXPIRED, RATE_LIMITED, UNSUPPORTED, ERROR }

    /** Ask the server to text a 6-digit code to [phoneNumber]. */
    suspend fun requestSmsCode(phoneNumber: String, fcmToken: String): SmsStatus =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("phone_number", phoneNumber)
                    put("fcm_token", fcmToken)
                }.toString()
                val request = ServerApi.request("/verify-number/sms")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                ServerApi.client.newCall(request).execute().use { r ->
                    when {
                        r.isSuccessful -> SmsStatus.SENT
                        r.code == 429  -> SmsStatus.RATE_LIMITED
                        r.code == 400  -> SmsStatus.UNSUPPORTED
                        else           -> SmsStatus.ERROR
                    }
                }
            } catch (e: Exception) {
                SmsStatus.ERROR
            }
        }

    /** Check the texted [code]. Returns (status, ticket); the ticket is set only on OK. */
    suspend fun checkSmsCode(phoneNumber: String, fcmToken: String, code: String): Pair<SmsStatus, String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("phone_number", phoneNumber)
                    put("fcm_token", fcmToken)
                    put("code", code)
                }.toString()
                val request = ServerApi.request("/verify-number/sms-check")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                ServerApi.client.newCall(request).execute().use { r ->
                    val json = runCatching { JSONObject(r.body?.string() ?: "{}") }.getOrDefault(JSONObject())
                    val ticket = json.optString("ticket")
                    when {
                        r.isSuccessful && ticket.isNotEmpty() -> SmsStatus.OK to ticket
                        json.optString("error") == "wrong"    -> SmsStatus.WRONG to ""
                        r.code == 400                         -> SmsStatus.EXPIRED to ""
                        else                                  -> SmsStatus.ERROR to ""
                    }
                }
            } catch (e: Exception) {
                SmsStatus.ERROR to ""
            }
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Asks the server to delete all data associated with this phone number.
     * Returns true on success or if the user was never registered on the server.
     */
    suspend fun deleteFromServer(): Boolean = withContext(Dispatchers.IO) {
        val phone = getPhoneNumber()
        if (phone.isEmpty()) return@withContext true
        try {
            val body = JSONObject().apply { put("phone_number", phone) }.toString()
            val request = ServerApi.request("/delete")
                .delete(body.toRequestBody("application/json".toMediaType()))
                .build()
            ServerApi.client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 404
            }
        } catch (e: Exception) {
            false
        }
    }
}
