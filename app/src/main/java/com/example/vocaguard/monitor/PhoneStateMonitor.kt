@file:Suppress("DEPRECATION") // PhoneStateListener retained for API < 31; TelephonyCallback used on 31+

package com.example.vocaguard.monitor

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.example.vocaguard.data.ScamDatabaseManager

@Suppress("DEPRECATION") // PhoneStateListener used only on API < 31; TelephonyCallback used on 31+
class PhoneStateMonitor(private val context: Context) {

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Use TelephonyCallback for Android 12+
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChange(state)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                // Only assign after successful registration so stopMonitoring never
                // tries to unregister a callback that was never registered.
                telephonyCallback = callback
            } else {
                // Use PhoneStateListener for older versions
                phoneStateListener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in API 31")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallStateChange(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
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

    private fun handleCallStateChange(state: Int, phoneNumber: String? = null) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(TAG, "Incoming call: ${phoneNumber ?: "unknown"}")
                onIncomingCall(phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call answered or outgoing call")
                onCallActive()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "Call ended or idle")
                onCallEnded()
            }
        }
    }

    private fun onIncomingCall(phoneNumber: String?) {
        // This is already handled by CallScreeningService
        // But we can use it as a backup or for additional logging
        Log.d(TAG, "Incoming call detected via PhoneStateMonitor: $phoneNumber")
    }

    private fun onCallActive() {
        // Call is now active
        // The AccessibilityService will handle starting the monitoring
        Log.d(TAG, "Call is now active")
    }

    private fun onCallEnded() {
        Log.d(TAG, "Call ended")
        val number = scamDatabaseManager.activeCallPhoneNumber
        if (number.isNotEmpty()) {
            scamDatabaseManager.stopMonitoringCall(number)
        }
    }
}