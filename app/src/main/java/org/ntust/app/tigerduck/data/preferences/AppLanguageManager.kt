package org.ntust.app.tigerduck.data.preferences

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageManager {
    const val SYSTEM = "system"

    // Common tags we special-case for better default matching.
    private const val SIMPLIFIED_CHINESE = "zh-Hans"
    private const val TRADITIONAL_CHINESE = "zh-Hant"

    fun normalize(language: String): String {
        if (language == SYSTEM) return SYSTEM

        // Accept any valid BCP-47 tag. We keep this loose because the app
        // ships a large set of Android resource alias directories (values-*)
        // and we don't want to maintain a hard-coded allowlist in Kotlin.
        val parsed = Locale.forLanguageTag(language)
        return if (parsed.language.isNullOrBlank() || parsed.language == "und") SYSTEM else language
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

    /**
     * The NTUST course APIs only support Chinese and English.
     *
     * - Any Chinese UI language (Simplified/Traditional) should map to "zh".
     * - Any non-Chinese UI language should map to "en".
     * - "Follow system" is resolved via [resolvedSystemLanguage].
     */
    fun resolvedCourseApiLanguage(appLanguage: String): String {
        val normalized = normalize(appLanguage)
        if (normalized == SYSTEM) return resolvedSystemLanguage()

        // Any Chinese UI locale (zh-*) should use Chinese course names.
        val locale = Locale.forLanguageTag(normalized)
        return if (locale.language.equals("zh", ignoreCase = true)) "zh" else "en"
    }

    fun isCourseApiEnglish(appLanguage: String): Boolean = resolvedCourseApiLanguage(appLanguage) == "en"

    fun apply(language: String) {
        val normalized = normalize(language)
        val locales = when (normalized) {
            // SYSTEM: empty list lets Android track the device locale live.
            // Pinning a tag here would persist across a system-locale change
            // and make "Follow system" stop following.
            SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            // Prefer region defaults so Android can match the generated
            // resource qualifiers (values-zh-rTW / values-zh-rCN).
            TRADITIONAL_CHINESE -> LocaleListCompat.forLanguageTags("zh-Hant-TW")
            SIMPLIFIED_CHINESE -> LocaleListCompat.forLanguageTags("zh-Hans-CN")
            else -> LocaleListCompat.forLanguageTags(normalized)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
