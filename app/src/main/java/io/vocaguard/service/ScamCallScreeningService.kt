package io.vocaguard.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.ui.ScamOverlayManager

class ScamCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "ScamCallScreening"
    }

    private val overlayManager by lazy { ScamOverlayManager(applicationContext) }
    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: ""
        Log.d(TAG, "Screening call from: $phoneNumber")

        // When call forwarding is active, the server already handles scam detection
        // and sends FCM alerts. All incoming PSTN calls arrive from the server's DID,
        // so local screening would only generate false positives — skip it entirely.
        if (DetectionSettings.getInstance(applicationContext).callForwardingEnabled) {
            Log.i(TAG, "Call forwarding active — skipping local screening, server handles detection")
            respondToCall(
                callDetails,
                CallResponse.Builder().setDisallowCall(false).setRejectCall(false).build()
            )
            return
        }

        val scamDatabaseManager = ScamDatabaseManager.getInstance(applicationContext)
        val scamInfo = scamDatabaseManager.checkNumber(phoneNumber)

        // Always allow the call to ring — we warn via overlay instead of silent blocking
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .build()
        )

        // Only show the incoming call overlay for known scammers — showing it for every
        // call blocks Samsung's Answer button and confuses the user.
        if (phoneNumber.isNotBlank() && scamInfo.isKnownScammer) {
            overlayManager.showIncomingCall(
                phoneNumber = phoneNumber,
                isScam = true,
                scamType = scamInfo.scamType
            )
            registerCallEndListener()
        }

        // Start audio monitoring for non-scammer calls (unknown / suspicious)
        if (!scamInfo.isKnownScammer) {
            scamDatabaseManager.markCallForMonitoring(phoneNumber, scamInfo.isSuspicious)
            startForegroundService(
                Intent(applicationContext, CallMonitoringService::class.java).apply {
                    action = CallMonitoringService.ACTION_START_MONITORING
                }
            )
        }
    }

    /** Registers a one-shot listener that dismisses the overlay when the call ends. */
    private fun registerCallEndListener() {
        val tm = getSystemService(TelephonyManager::class.java)
        unregisterCallEndListener(tm)  // clean up any previous listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        overlayManager.dismissIncomingCall()
                        tm.unregisterTelephonyCallback(this)
                        telephonyCallback = null
                    }
                }
            }
            telephonyCallback = callback
            tm.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCallStateChanged(state: Int, number: String?) {
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        overlayManager.dismissIncomingCall()
                        @Suppress("DEPRECATION")
                        tm.listen(this, PhoneStateListener.LISTEN_NONE)
                        phoneStateListener = null
                    }
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterCallEndListener(tm: TelephonyManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            phoneStateListener = null
        }
    }
}
