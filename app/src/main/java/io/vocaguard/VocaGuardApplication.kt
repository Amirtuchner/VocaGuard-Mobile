package io.vocaguard

import android.app.Application
import io.vocaguard.service.VocaGuardSipManager

class VocaGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep SIP endpoint registered so Asterisk can reach us immediately when a call is accepted
        VocaGuardSipManager.initialize(this)
    }
}
