package com.example.vocaguard.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.vocaguard.R
import com.example.vocaguard.data.ScamType

/**
 * Displays a translucent warning banner at the top of the screen when a scam is
 * detected during a live call.
 *
 * Requires the `SYSTEM_ALERT_WINDOW` permission. Call [canShowOverlay] before [show].
 * The overlay auto-dismisses after [AUTO_DISMISS_MS] milliseconds and can be removed
 * early with [dismiss].
 */
class ScamOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "ScamOverlayManager"
        private const val AUTO_DISMISS_MS = 8_000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private val autoDismissRunnable = Runnable { dismiss() }

    /** True if the app has the SYSTEM_ALERT_WINDOW permission. */
    fun canShowOverlay(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Show (or update) the scam warning banner.
     * Must be called from the main thread or a thread that can post to it.
     */
    fun show(scamType: ScamType, confidence: Float) {
        if (!canShowOverlay()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay skipped")
            return
        }

        mainHandler.post {
            // Dismiss any existing overlay first
            removeOverlayView()

            val view = LayoutInflater.from(context).inflate(R.layout.overlay_scam_alert, null)
            view.findViewById<TextView>(R.id.overlay_title)?.text =
                "⚠ SCAM DETECTED: ${formatScamType(scamType)}"
            view.findViewById<TextView>(R.id.overlay_subtitle)?.text =
                "${(confidence * 100).toInt()}% confidence — do not share personal info"

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            }

            try {
                windowManager.addView(view, params)
                overlayView = view
                Log.d(TAG, "Scam overlay shown for $scamType")

                // Schedule auto-dismiss
                mainHandler.removeCallbacks(autoDismissRunnable)
                mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay view", e)
            }
        }
    }

    /** Remove the overlay banner immediately. */
    fun dismiss() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoDismissRunnable)
            removeOverlayView()
        }
    }

    private fun removeOverlayView() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) { /* already removed */ }
            overlayView = null
        }
    }

    private fun formatScamType(scamType: ScamType): String = when (scamType) {
        ScamType.IRS_SCAM -> "IRS Scam"
        ScamType.TECH_SUPPORT -> "Tech Support"
        ScamType.BANK_FRAUD -> "Bank Fraud"
        ScamType.LOTTERY_PRIZE -> "Lottery"
        ScamType.SOCIAL_SECURITY -> "Social Security"
        ScamType.ROBOCALL -> "Robocall"
        ScamType.PHISHING -> "Phishing"
        ScamType.INSURANCE -> "Insurance"
        ScamType.INVESTMENT_SCAM -> "Investment Scam"
        ScamType.DONATION_FRAUD -> "Donation Fraud"
        ScamType.UNKNOWN -> "Suspicious Call"
    }
}
