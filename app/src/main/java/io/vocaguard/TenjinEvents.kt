package io.vocaguard

import android.content.Context
import android.util.Log
import com.tenjin.android.TenjinSDK

/**
 * Thin wrapper for Tenjin custom events. No-ops when no API key is configured
 * (BuildConfig.TENJIN_API_KEY empty), and never lets an analytics failure crash
 * the app. Event names are the conversion funnel we track in Tenjin.
 */
object TenjinEvents {
    const val ONBOARDING_COMPLETE = "onboarding_complete"
    const val REGISTERED = "registered"
    const val CALL_FORWARDING_ENABLED = "call_forwarding_enabled"
    const val SUBSCRIBED = "subscribed"
    const val SCAM_DETECTED = "scam_detected"

    fun send(context: Context, name: String) {
        if (BuildConfig.TENJIN_API_KEY.isEmpty()) return
        try {
            TenjinSDK.getInstance(context.applicationContext, BuildConfig.TENJIN_API_KEY)
                .eventWithName(name)
        } catch (e: Exception) {
            Log.w("TenjinEvents", "event '$name' failed: ${e.message}")
        }
    }
}
