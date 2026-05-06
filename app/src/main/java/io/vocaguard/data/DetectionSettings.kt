package io.vocaguard.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user-configurable detection preferences.
 *
 * [sensitivity] is an integer 0–100 that maps to a detection confidence threshold:
 *   0  (lowest)  → threshold 0.90 (fewest alerts, very certain before alerting)
 *   50 (default) → threshold 0.65
 *   100 (highest) → threshold 0.40 (most alerts, flags anything suspicious)
 *
 * [locale] is a BCP-47 language tag used for TTS and SpeechRecognizer (default "en-US").
 *
 * [onboardingComplete] is set to true after the user finishes the first-launch walkthrough.
 */
class DetectionSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sensitivity: Int
        get() = prefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 100)).apply()

    /** Confidence threshold derived from sensitivity (0.40 – 0.90). */
    val confidenceThreshold: Float
        get() = 0.90f - (sensitivity / 100f) * 0.50f

    /** BCP-47 locale tag for TTS and SpeechRecognizer (e.g. "en-US", "es-ES"). */
    var locale: String
        get() = prefs.getString(KEY_LOCALE, DEFAULT_LOCALE) ?: DEFAULT_LOCALE
        set(value) = prefs.edit().putString(KEY_LOCALE, value).apply()

    /** True once the user has completed the onboarding walkthrough. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** Whether to read scam alerts aloud via TTS during a call. */
    var enableTts: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_TTS, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_TTS, value).apply()

    /** Whether to play the alarm-tone beeps when a scam is detected. */
    var enableSound: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_SOUND, value).apply()

    /** User's preferred app theme: "system", "dark", or "light". */
    var themePreference: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /** Whether to vibrate the phone when a scam is detected. */
    var enableVibration: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_VIBRATION, value).apply()

    /**
     * Whether to scan WhatsApp and Telegram notifications for scam message text.
     * Requires Notification Access (granted via system Settings).
     */
    var messageScanEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESSAGE_SCAN, true)
        set(value) = prefs.edit().putBoolean(KEY_MESSAGE_SCAN, value).apply()

    // ── Per-scam-type alert toggles ────────────────────────────────────────────

    /**
     * Returns whether alerts are enabled for [scamType].
     * All types are enabled by default; users can silence specific categories they trust.
     */
    fun isAlertEnabledFor(scamType: ScamType): Boolean =
        prefs.getBoolean("alert_type_${scamType.name}", true)

    /** Enables or disables alerts for a specific [scamType]. */
    fun setAlertEnabled(scamType: ScamType, enabled: Boolean) =
        prefs.edit().putBoolean("alert_type_${scamType.name}", enabled).apply()

    // ── Report submission endpoint ─────────────────────────────────────────────

    /**
     * Optional HTTPS endpoint that receives user-reported scam numbers as JSON POSTs.
     * Empty string means submission is disabled.
     */
    var reportEndpointUrl: String
        get() = prefs.getString(KEY_REPORT_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPORT_ENDPOINT, value).apply()

    companion object {
        private const val PREFS_NAME = "vocaguard_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val DEFAULT_SENSITIVITY = 50
        private const val KEY_LOCALE = "locale"
        const val DEFAULT_LOCALE = "en-US"
        private const val KEY_ONBOARDING = "onboarding_complete"
        private const val KEY_ENABLE_TTS = "enable_tts"
        private const val KEY_ENABLE_SOUND = "enable_sound"
        private const val KEY_ENABLE_VIBRATION = "enable_vibration"
        private const val KEY_MESSAGE_SCAN = "message_scan_enabled"
        private const val KEY_REPORT_ENDPOINT = "report_endpoint_url"
        private const val KEY_THEME = "theme_preference"
        /** Valid values: "system", "dark", "light". */
        const val THEME_SYSTEM = "system"
        const val THEME_DARK   = "dark"
        const val THEME_LIGHT  = "light"

        /** Supported locales shown in the Settings UI. */
        val SUPPORTED_LOCALES = listOf(
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "es-ES" to "Spanish",
            "fr-FR" to "French",
            "de-DE" to "German",
            "zh-CN" to "Chinese (Simplified)",
            "ja-JP" to "Japanese",
            "he-IL" to "Hebrew",
            "ru-RU" to "Russian",
            "ar-SA" to "Arabic",
            "it-IT" to "Italian"
        )

        @Volatile
        private var instance: DetectionSettings? = null

        fun getInstance(context: Context): DetectionSettings {
            return instance ?: synchronized(this) {
                instance ?: DetectionSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}
