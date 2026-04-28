package io.vocaguard.ui

import android.util.Log

/**
 * Thin wrapper around Firebase Crashlytics that degrades gracefully when
 * Firebase is not configured (i.e. `google-services.json` is absent).
 *
 * **To fully enable Crashlytics:**
 * 1. Create a Firebase project at console.firebase.google.com and register the app.
 * 2. Place `app/google-services.json` in this module.
 * 3. In `app/build.gradle.kts`, apply both plugins:
 *    ```kotlin
 *    id("com.google.gms.google-services")
 *    id("com.google.firebase.crashlytics")
 *    ```
 * 4. In the root `build.gradle.kts` (or `settings.gradle.kts` plugin management block),
 *    declare the plugin versions.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private var enabled = false

    /**
     * Call once from [android.app.Application.onCreate] or [android.app.Activity.onCreate].
     * Safe to call even when Firebase is not configured — it will silently no-op.
     */
    fun init() {
        try {
            // Accessing getInstance() throws IllegalStateException if FirebaseApp is not
            // initialised (which happens when google-services.json is absent).
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            enabled = true
            Log.i(TAG, "Firebase Crashlytics initialised")
        } catch (t: Throwable) {
            Log.d(TAG, "Crashlytics not available (add google-services.json to enable): ${t.message}")
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
