package io.vocaguard.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Helpers to obtain the user's own phone number for the simplified onboarding,
 * so they rarely have to type it:
 *  1. [readSimNumber] — silent best-effort via the telephony APIs (often empty:
 *     carriers frequently don't populate the SIM's MSISDN, especially outside the US).
 *  2. [phoneNumberHintIntent] — Google's one-tap Phone Number Hint picker
 *     (no runtime permission, no typing). The caller launches the IntentSender and
 *     passes the result to [parseHintResult].
 * Manual entry remains the final fallback.
 */
object PhoneNumberHelper {
    private const val TAG = "PhoneNumberHelper"

    /** Silent, best-effort read of the device's own number. May return "" — callers
     *  must fall back to the hint picker / manual entry. Needs READ_PHONE_STATE
     *  (already granted); on API 30+ a reliable read would need READ_PHONE_NUMBERS,
     *  which we deliberately don't request — the hint picker covers that case. */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun readSimNumber(context: Context): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val tm = context.getSystemService(TelephonyManager::class.java)
                val subId = SubscriptionManager.getDefaultSubscriptionId()
                if (sm != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    val n = sm.getPhoneNumber(subId)
                    if (!n.isNullOrBlank()) return n
                }
                @Suppress("DEPRECATION")
                tm?.line1Number?.let { if (it.isNotBlank()) return it }
            } else {
                val tm = context.getSystemService(TelephonyManager::class.java)
                @Suppress("DEPRECATION")
                tm?.line1Number?.let { if (it.isNotBlank()) return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readSimNumber failed: ${e.message}")
        }
        return ""
    }

    /** IntentSender for the one-tap Phone Number Hint picker, or null if unavailable
     *  (no Play Services / no SIM number). Launch it with StartIntentSenderForResult. */
    suspend fun phoneNumberHintIntent(context: Context): android.content.IntentSender? =
        suspendCancellableCoroutine { cont ->
            try {
                val request = GetPhoneNumberHintIntentRequest.builder().build()
                Identity.getSignInClient(context)
                    .getPhoneNumberHintIntent(request)
                    .addOnSuccessListener { pi ->
                        if (cont.isActive) cont.resume(pi.intentSender)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "phoneNumberHintIntent unavailable: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "phoneNumberHintIntent error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Extract the chosen number from the hint picker's result intent (or ""). */
    fun parseHintResult(context: Context, data: Intent?): String {
        return try {
            if (data == null) "" else
                Identity.getSignInClient(context).getPhoneNumberFromIntent(data) ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "parseHintResult failed: ${e.message}")
            ""
        }
    }
}
