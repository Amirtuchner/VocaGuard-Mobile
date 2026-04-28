package io.vocaguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CallAudioAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAudioAccessibility"
        private val PHONE_PACKAGES = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.samsung.android.incallui"
        )
    }

    private var isCallActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        // Check if event is from a phone app
        if (PHONE_PACKAGES.any { packageName.contains(it, ignoreCase = true) }) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handlePhoneWindowEvent(event)
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    handlePhoneNotificationEvent(event)
                }
            }
        }
    }

    private fun handlePhoneWindowEvent(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: return

        // Detect if in-call UI is active
        val isInCallUI = className.contains("InCallActivity", ignoreCase = true) ||
                         className.contains("CallActivity", ignoreCase = true)

        if (isInCallUI && !isCallActive) {
            Log.i(TAG, "Phone call detected - starting monitoring")
            isCallActive = true
            startCallMonitoring()
        } else if (!isInCallUI && isCallActive) {
            Log.i(TAG, "Phone call ended - stopping monitoring")
            isCallActive = false
            stopCallMonitoring()
        }
    }

    private fun handlePhoneNotificationEvent(event: AccessibilityEvent) {
        // Handle ongoing call notifications
        val text = event.text?.toString() ?: return

        if (text.contains("ongoing call", ignoreCase = true) ||
            text.contains("call in progress", ignoreCase = true)) {
            if (!isCallActive) {
                Log.i(TAG, "Ongoing call detected via notification")
                isCallActive = true
                startCallMonitoring()
            }
        }
    }

    private fun startCallMonitoring() {
        // Start the foreground service that will handle audio recording and analysis
        val intent = Intent(this, CallMonitoringService::class.java).apply {
            action = CallMonitoringService.ACTION_START_MONITORING
        }
        startForegroundService(intent)
    }

    private fun stopCallMonitoring() {
        // Stop the monitoring service
        val intent = Intent(this, CallMonitoringService::class.java).apply {
            action = CallMonitoringService.ACTION_STOP_MONITORING
        }
        startService(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        if (isCallActive) {
            stopCallMonitoring()
            isCallActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        if (isCallActive) {
            stopCallMonitoring()
        }
    }
}
