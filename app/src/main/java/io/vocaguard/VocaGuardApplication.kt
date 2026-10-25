package io.vocaguard

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

        // Tenjin attribution core events. connect() sends the Install event (first
        // ever call) and an App Open event (each call). Calling it from
        // ProcessLifecycleOwner's ON_START fires it on every app foreground — not just
        // cold starts — so App Open counts are accurate (Tenjin's required setup).
        // Skipped if no API key is configured (local.properties: tenjin_api_key=...).
        if (BuildConfig.TENJIN_API_KEY.isNotEmpty()) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    try {
                        val tenjin = TenjinSDK.getInstance(this@VocaGuardApplication, BuildConfig.TENJIN_API_KEY)
                        // Tell Tenjin the install source is Google Play (otherwise it logs
                        // "source_app_store=unspecified", hurting attribution accuracy).
                        tenjin.setAppStore(TenjinSDK.AppStoreType.googleplay)
                        tenjin.connect()
                    } catch (e: Exception) {
                        Log.w("VocaGuardApp", "Tenjin connect failed: ${e.message}")
                    }
                }
            })
        }
    }
}
