package com.example.vocaguard.data

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

    companion object {
        private const val PREFS_NAME = "vocaguard_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val DEFAULT_SENSITIVITY = 50
        private const val KEY_LOCALE = "locale"
        const val DEFAULT_LOCALE = "en-US"
        private const val KEY_ONBOARDING = "onboarding_complete"

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