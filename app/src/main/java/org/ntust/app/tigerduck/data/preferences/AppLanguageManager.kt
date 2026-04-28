package org.ntust.app.tigerduck.data.preferences

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageManager {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val TRADITIONAL_CHINESE = "zh-Hant"

    private val supportedLanguages = setOf(SYSTEM, ENGLISH, TRADITIONAL_CHINESE)

    fun normalize(language: String): String {
        return if (language in supportedLanguages) language else SYSTEM
    }

    /**
     * Returns "zh" or "en" — the language code the app should use when the
     * user has chosen "Follow system". The device's primary locale wins if
     * it's one we localize for; otherwise we fall back to English so a
     * Japanese (or any other unsupported) device doesn't get the default
     * Chinese strings.
     */
    fun resolvedSystemLanguage(): String {
        val device = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
        return if (device.language.equals("zh", ignoreCase = true)) "zh" else "en"
    }

    fun apply(language: String) {
        val locales = when (normalize(language)) {
            ENGLISH -> LocaleListCompat.forLanguageTags("en")
            TRADITIONAL_CHINESE -> LocaleListCompat.forLanguageTags("zh-Hant-TW")
            // SYSTEM: empty list lets Android track the device locale live.
            // Pinning a tag here would persist across a system-locale change
            // and make "Follow system" stop following.
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
