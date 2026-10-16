package io.vocaguard

import android.content.Context
import android.content.res.Configuration
import io.vocaguard.data.DetectionSettings
import java.util.Locale

/**
 * Applies the user-selected app language at the Context level (attachBaseContext),
 * so all string resources resolve to that language. The single source of truth is
 * [DetectionSettings.locale] (a BCP-47 tag like "en-US" / "he-IL" / "ru-RU"), which
 * also drives the spoken-alert (TTS) voice — so one choice controls both text and voice.
 *
 * Phase 1: the mechanism is wired end-to-end. Until the UI strings are extracted into
 * per-language resource files (values-iw/, values-ru/), non-English selections simply
 * fall back to the default (English) resources — no visible text change yet, no risk.
 */
object LocaleHelper {

    /** Map a voice/BCP-47 locale tag to the UI resource language code. */
    fun uiLanguage(localeTag: String): String = when {
        localeTag.startsWith("he", ignoreCase = true) ||
            localeTag.startsWith("iw", ignoreCase = true) -> "iw"   // Android's code for Hebrew
        localeTag.startsWith("ru", ignoreCase = true) -> "ru"
        else -> "en"
    }

    /** Wrap a base context so its resources use the selected UI language. */
    fun wrap(base: Context): Context {
        val tag = runCatching { DetectionSettings.getInstance(base).locale }.getOrDefault("en-US")
        val locale = Locale(uiLanguage(tag))
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
