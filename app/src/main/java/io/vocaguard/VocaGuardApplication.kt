package io.vocaguard

import android.app.Application
import io.vocaguard.service.ServerDetectionManager
import io.vocaguard.service.VocaGuardSipManager

class VocaGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load registration prefs before SIP so credentials are available at init time
        ServerDetectionManager.init(this)
        // Keep SIP endpoint registered so Asterisk can reach us immediately when a call is accepted
        VocaGuardSipManager.initialize(this)
    }
}
