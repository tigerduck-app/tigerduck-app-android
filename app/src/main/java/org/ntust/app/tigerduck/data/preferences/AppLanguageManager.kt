package org.ntust.app.tigerduck.data.preferences

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageManager {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val TRADITIONAL_CHINESE = "zh-Hant"

    private val supportedLanguages = setOf(SYSTEM, ENGLISH, TRADITIONAL_CHINESE)

    fun normalize(language: String): String {
        return if (language in supportedLanguages) language else SYSTEM
    }

    fun apply(language: String) {
        val normalized = normalize(language)
        val locales = when (normalized) {
            ENGLISH -> LocaleListCompat.forLanguageTags("en")
            TRADITIONAL_CHINESE -> LocaleListCompat.forLanguageTags("zh-Hant-TW")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
