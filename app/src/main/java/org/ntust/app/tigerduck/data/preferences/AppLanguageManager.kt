package org.ntust.app.tigerduck.data.preferences

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager.resolvedSystemLanguage
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
        return if (isSiniticLanguage(device.language)) "zh" else "en"
    }

    /**
     * The NTUST course APIs only support Chinese and English.
     *
     * - Any Sinitic UI language (Mandarin, Cantonese, Hakka, Min, Wu, Literary
     *   Chinese, …) should map to "zh" so course names render in Mandarin
     *   instead of being run through the English abbreviation map.
     * - Any non-Sinitic UI language should map to "en".
     * - "Follow system" is resolved via [resolvedSystemLanguage].
     */
    fun resolvedCourseApiLanguage(appLanguage: String): String {
        val normalized = normalize(appLanguage)
        if (normalized == SYSTEM) return resolvedSystemLanguage()

        val locale = Locale.forLanguageTag(normalized)
        return if (isSiniticLanguage(locale.language)) "zh" else "en"
    }

    /** ISO 639 codes for the Sinitic family — not just Mandarin (`zh`). */
    private val SINITIC_LANGUAGE_CODES = setOf(
        "zh",   // Mandarin (zh-Hant, zh-Hans, zh-TW, zh-CN, zh-HK, …)
        "yue",  // Cantonese
        "nan",  // Min Nan / Hokkien / Taiwanese
        "hak",  // Hakka
        "wuu",  // Wu / Shanghainese
        "lzh",  // Literary / Classical Chinese
        "cmn",  // explicit Mandarin (rarely used; some BCP-47 strict tags)
        "cdo",  // Min Dong
        "cjy",  // Jin
        "czh",  // Huizhou
        "cpx",  // Pu-Xian Min
        "gan",  // Gan
        "hsn",  // Xiang
        "mnp",  // Min Bei
    )

    private fun isSiniticLanguage(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        return code.lowercase() in SINITIC_LANGUAGE_CODES
    }

    fun isCourseApiEnglish(appLanguage: String): Boolean =
        resolvedCourseApiLanguage(appLanguage) == "en"

    fun apply(language: String) {
        AppCompatDelegate.setApplicationLocales(toLocaleList(language))
    }

    /**
     * Resolve the user's chosen language to a concrete [Locale] for one-shot
     * use (e.g. notification-channel name lookup before
     * [AppCompatDelegate.setApplicationLocales] takes effect on first launch).
     * Returns null when the user has chosen "Follow system" — callers should
     * fall back to the platform default locale.
     */
    fun resolveExplicitLocale(language: String): Locale? {
        val list = toLocaleList(language)
        if (list.isEmpty) return null
        return list[0]
    }

    private fun toLocaleList(language: String): LocaleListCompat {
        return when (val normalized = normalize(language)) {
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
    }
}
