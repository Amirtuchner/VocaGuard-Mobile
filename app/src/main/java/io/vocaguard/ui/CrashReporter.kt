package io.vocaguard.ui

import android.content.Context
import android.util.Log
import io.vocaguard.service.ErrorReporter

/**
 * Thin wrapper around Firebase Crashlytics that degrades gracefully when
 * Firebase is not configured (i.e. `google-services.json` is absent).
 *
 * Also installs a global uncaught exception handler that reports crashes
 * to the VocaGuard server via [ErrorReporter].
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private var enabled = false
    private var appContext: Context? = null

    /**
     * Call once from [android.app.Activity.onCreate].
     * Safe to call even when Firebase is not configured — it will silently no-op.
     */
    fun init(context: Context? = null) {
        if (context != null) {
            appContext = context.applicationContext
            installUncaughtHandler()
        }
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            enabled = true
            Log.i(TAG, "Firebase Crashlytics initialised")
        } catch (t: Throwable) {
            Log.d(TAG, "Crashlytics not available (add google-services.json to enable): ${t.message}")
        }
    }

    private fun installUncaughtHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ctx = appContext
                if (ctx != null) {
                    val msg = "CRASH: ${throwable.javaClass.simpleName}: ${throwable.message}"
                    ErrorReporter.report(ctx, msg)
                    // Give the background thread a moment to send
                    Thread.sleep(2000)
                }
            } catch (_: Throwable) { }
            // Pass to the default handler (shows crash dialog / kills app)
            default?.uncaughtException(thread, throwable)
        }
    }

    /** Log a breadcrumb message visible in the Crashlytics console. */
    fun log(message: String) {
        if (!enabled) return
        try { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log(message) }
        catch (_: Throwable) {}
    }

    /** Record a non-fatal exception. */
    fun recordException(t: Throwable) {
        if (!enabled) return
        try { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(t) }
        catch (_: Throwable) {}
    }

    /** Set a custom key for crash reports. */
    fun setCustomKey(key: String, value: String) {
        if (!enabled) return
        try { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
        catch (_: Throwable) {}
    }
}
