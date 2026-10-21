package io.vocaguard

import android.app.Application
import android.content.Context
import android.util.Log
import com.tenjin.android.TenjinSDK
import io.vocaguard.service.ServerDetectionManager
import io.vocaguard.service.VocaGuardSipManager

class VocaGuardApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Load registration prefs before SIP so credentials are available at init time
        ServerDetectionManager.init(this)
        // Keep SIP endpoint registered so Asterisk can reach us immediately when a call is accepted
        VocaGuardSipManager.initialize(this)

        // Tenjin attribution — fires install/open + attribution. Skipped if no API key
        // is configured (local.properties: tenjin_api_key=...).
        if (BuildConfig.TENJIN_API_KEY.isNotEmpty()) {
            try {
                TenjinSDK.getInstance(this, BuildConfig.TENJIN_API_KEY).connect()
            } catch (e: Exception) {
                Log.w("VocaGuardApp", "Tenjin init failed: ${e.message}")
            }
        }
    }
}
