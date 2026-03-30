package com.example.vocaguard.service

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.example.vocaguard.data.ScamDatabaseManager

class ScamCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "ScamCallScreening"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        Log.d(TAG, "Screening call from: ${callDetails.handle}")

        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: ""

        // Block calls with no caller ID (hidden, restricted, or truly unknown number).
        // When a caller withholds their number Android sets handle to null, so phoneNumber is blank.
        val isUnknownCaller = phoneNumber.isBlank()

        // Check against scam database
        val scamDatabaseManager = ScamDatabaseManager.getInstance(applicationContext)
        val scamInfo = scamDatabaseManager.checkNumber(phoneNumber)

        val response = CallResponse.Builder()

        when {
            isUnknownCaller -> {
                // Block calls with no caller ID
                Log.w(TAG, "Blocking unknown/hidden caller")
                response
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
            }
            scamInfo.isKnownScammer -> {
                // Block known scammers
                Log.w(TAG, "Blocking known scammer: $phoneNumber")
                response
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
            }
            scamInfo.isSuspicious -> {
                // Allow but mark as suspicious for monitoring
                Log.i(TAG, "Allowing suspicious number: $phoneNumber")
                response
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .setSilenceCall(false)
            }
            else -> {
                // Allow normal calls
                Log.d(TAG, "Allowing normal call: $phoneNumber")
                response
                    .setDisallowCall(false)
                    .setRejectCall(false)
            }
        }

        respondToCall(callDetails, response.build())

        // If suspicious or allowed (and not unknown), start monitoring service
        if (!isUnknownCaller && !scamInfo.isKnownScammer) {
            scamDatabaseManager.markCallForMonitoring(phoneNumber, scamInfo.isSuspicious)
            val monitoringIntent = Intent(applicationContext, CallMonitoringService::class.java).apply {
                action = CallMonitoringService.ACTION_START_MONITORING
            }
            startForegroundService(monitoringIntent)
        }
    }
}