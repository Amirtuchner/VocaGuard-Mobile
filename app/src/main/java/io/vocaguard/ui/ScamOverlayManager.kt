package io.vocaguard.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.vocaguard.R
import io.vocaguard.data.ScamType

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
    private var incomingCallView: View? = null

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

            view.findViewById<Button>(R.id.btn_dismiss_overlay)?.setOnClickListener {
                mainHandler.removeCallbacks(autoDismissRunnable)
                removeOverlayView()
            }
            view.findViewById<Button>(R.id.btn_not_a_scam)?.setOnClickListener {
                Log.i(TAG, "User marked as false positive: $scamType (${(confidence * 100).toInt()}%)")
                mainHandler.removeCallbacks(autoDismissRunnable)
                removeOverlayView()
            }

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
            removeIncomingCallView()
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

    /** Show an incoming-call overlay with the caller's number and optional scam warning. */
    fun showIncomingCall(phoneNumber: String, isScam: Boolean, scamType: ScamType?) {
        if (!canShowOverlay()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — incoming call overlay skipped")
            return
        }
        mainHandler.post {
            removeIncomingCallView()
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_incoming_call, null)
            view.findViewById<TextView>(R.id.incoming_number)?.text =
                phoneNumber.ifBlank { "Unknown Number" }
            val warningView = view.findViewById<TextView>(R.id.incoming_scam_warning)
            if (isScam && scamType != null) {
                warningView?.text = "⚠ SCAM CALL DETECTED\n${formatScamType(scamType)}\nDo not answer!"
                warningView?.visibility = View.VISIBLE
            } else {
                warningView?.visibility = View.GONE
            }
            view.findViewById<Button>(R.id.btn_decline_incoming)?.setOnClickListener {
                try {
                    val tm = context.getSystemService(TelecomManager::class.java)
                    @Suppress("DEPRECATION")
                    tm.endCall()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decline call", e)
                }
                dismissIncomingCall()
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL }
            try {
                windowManager.addView(view, params)
                incomingCallView = view
                Log.d(TAG, "Incoming call overlay shown: $phoneNumber isScam=$isScam")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add incoming call overlay", e)
            }
        }
    }

    /** Remove the incoming call overlay (call when the call is answered, declined, or ends). */
    fun dismissIncomingCall() {
        mainHandler.post { removeIncomingCallView() }
    }

    private fun removeIncomingCallView() {
        incomingCallView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { /* already removed */ }
            incomingCallView = null
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
        ScamType.ROMANCE_SCAM -> "Romance Scam"
        ScamType.DELIVERY_SCAM -> "Delivery Scam"
        ScamType.JOB_SCAM -> "Job Scam"
        ScamType.SOCIAL_ENGINEERING -> "Social Engineering"
        ScamType.UNKNOWN -> "Suspicious Call"
    }
}
