package io.vocaguard.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
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
        }
        // Always register call-end listener to notify PhoneMonitorService when call ends
        registerCallEndListener()

        // Mark call for monitoring and notify PhoneMonitorService to start audio
        // monitoring. We can't start a microphone FGS from here (background restriction
        // on Android 14+), but PhoneMonitorService already has a running FGS that can
        // upgrade to include microphone type.
        if (!scamInfo.isKnownScammer) {
            scamDatabaseManager.markCallForMonitoring(phoneNumber, scamInfo.isSuspicious)
        }
        // Directly tell PhoneMonitorService to start monitoring NOW.
        // Samsung freezes background processes, so we can't rely on delayed
        // OFFHOOK detection. Vosk will capture silence until the call is answered.
        Log.i(TAG, "Notifying PhoneMonitorService to start monitoring immediately")
        notifyPhoneMonitorService(PhoneMonitorService.ACTION_CALL_OFFHOOK)
    }

    /** Registers a one-shot listener that dismisses the overlay when the call ends. */
    private fun registerCallEndListener() {
        val tm = getSystemService(TelephonyManager::class.java)
        unregisterCallEndListener(tm)  // clean up any previous listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        Log.i(TAG, "Call answered (TelephonyCallback in ScreeningService)")
                        notifyPhoneMonitorService(PhoneMonitorService.ACTION_CALL_OFFHOOK)
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                        overlayManager.dismissIncomingCall()
                        notifyPhoneMonitorService(PhoneMonitorService.ACTION_CALL_IDLE)
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
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                        Log.i(TAG, "Call answered (PhoneStateListener in ScreeningService)")
                        notifyPhoneMonitorService(PhoneMonitorService.ACTION_CALL_OFFHOOK)
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                        overlayManager.dismissIncomingCall()
                        notifyPhoneMonitorService(PhoneMonitorService.ACTION_CALL_IDLE)
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

    private fun notifyPhoneMonitorService(action: String) {
        try {
            startService(
                android.content.Intent(this, PhoneMonitorService::class.java).apply {
                    this.action = action
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot notify PhoneMonitorService ($action): ${e.message}")
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
