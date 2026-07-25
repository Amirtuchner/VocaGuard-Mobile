@file:Suppress("DEPRECATION") // PhoneStateListener retained for API < 31; TelephonyCallback used on 31+

package io.vocaguard.monitor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import io.vocaguard.data.CallDirection
import io.vocaguard.data.DetectionSettings
import io.vocaguard.data.ScamDatabaseManager
import io.vocaguard.service.CallMonitoringService

@Suppress("DEPRECATION") // PhoneStateListener used only on API < 31; TelephonyCallback used on 31+
class PhoneStateMonitor(
    private val context: Context,
    private val onCallStarted: (() -> Unit)? = null,
    private val onCallEnded: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "PhoneStateMonitor"
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val scamDatabaseManager = ScamDatabaseManager.getInstance(context)
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var isMonitoring = false

    fun startMonitoring() {
        if (isMonitoring) return
        Log.d(TAG, "Starting phone state monitoring")

        try {
            // Force PhoneStateListener on all API levels — Samsung's TelephonyCallback
            // silently stops delivering events when the app is in background, even with FGS.
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in API 31")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallStateChange(state, phoneNumber)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "Registered PhoneStateListener on default subscription")

            // Also try TelephonyCallback as a backup (may work when activity is visible)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallStateChange(state)
                        }
                    }
                    telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                    telephonyCallback = callback
                    Log.i(TAG, "Also registered TelephonyCallback")
                } catch (e: Exception) {
                    Log.w(TAG, "TelephonyCallback registration failed: ${e.message}")
                }
            }
            isMonitoring = true
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE not granted; phone state monitoring deferred: ${e.message}")
        }
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        Log.d(TAG, "Stopping phone state monitoring")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
            telephonyCallback = null
        } else {
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
        isMonitoring = false
    }

    private var lastReportedState = -1
    // Set on RINGING, cleared on IDLE — lets OFFHOOK tell an answered incoming call
    // apart from a dialed outgoing call, since only incoming calls ring first.
    private var sawRingingForCurrentCall = false

    private fun handleCallStateChange(state: Int, phoneNumber: String? = null) {
        // Deduplicate — both PhoneStateListener and TelephonyCallback may fire
        if (state == lastReportedState) return
        lastReportedState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(TAG, "Incoming call: ${phoneNumber ?: "unknown"}")
                sawRingingForCurrentCall = true
                onIncomingCall(phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call answered or outgoing call")
                scamDatabaseManager.setActiveCallDirection(
                    if (sawRingingForCurrentCall) CallDirection.INCOMING else CallDirection.OUTGOING
                )
                onCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "Call ended or idle")
                sawRingingForCurrentCall = false
                handleCallEnded()
            }
        }
    }

    private fun onIncomingCall(phoneNumber: String?) {
        Log.i(TAG, "Incoming call ringing: $phoneNumber — waiting for answer before monitoring")
    }

    private fun onCallActive() {
        val settings = DetectionSettings.getInstance(context)
        if (!settings.callForwardingEnabled) {
            Log.i(TAG, "Call answered but call forwarding not enabled — skipping")
            return
        }
        Log.i(TAG, "Call answered — starting audio monitoring")
        if (onCallStarted != null) {
            // Use callback to run monitoring within the existing FGS (avoids background FGS restriction)
            onCallStarted.invoke()
        } else {
            // Legacy path: start separate FGS
            context.startForegroundService(
                Intent(context, CallMonitoringService::class.java).apply {
                    action = CallMonitoringService.ACTION_START_MONITORING
                }
            )
        }
    }

    private fun handleCallEnded() {
        Log.d(TAG, "Call ended")
        val number = scamDatabaseManager.activeCallPhoneNumber
        if (number.isNotEmpty()) {
            scamDatabaseManager.stopMonitoringCall(number)
        }
        onCallEnded?.invoke()
    }
}
