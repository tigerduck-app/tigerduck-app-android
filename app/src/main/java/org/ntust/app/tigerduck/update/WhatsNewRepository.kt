package org.ntust.app.tigerduck.update

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads maintainer-authored "What's new" content from `assets/whatsnew.json`.
 *
 * The JSON is an object keyed by versionCode string; each version holds a
 * per-locale map (`zh-TW`, `en`) of [WhatsNewContent].
 */
@Singleton
class WhatsNewRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * The [WhatsNewContent] for [versionCode] in the locale implied by
     * [languageTag], or null if the asset is missing/malformed, has no entry
     * for the version, or the entry is empty.
     */
    fun entryFor(versionCode: Int, languageTag: String): WhatsNewContent? {
        val json = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "whatsnew.json not readable", it)
            return null
        }
        return parse(json, versionCode, languageTag)
    }

    companion object {
        const val ASSET_NAME = "whatsnew.json"
        private const val TAG = "WhatsNewRepository"
        private val gson = Gson()

        /**
         * Pure parse step — no Android dependencies, unit-testable.
         *
         * Locale selection: a Chinese [languageTag] (`zh-*`) maps to the
         * `zh-TW` block; everything else falls back to `en`.
         */
        fun parse(json: String, versionCode: Int, languageTag: String): WhatsNewContent? {
            val type = object : TypeToken<Map<String, Map<String, WhatsNewContent>>>() {}.type
            val byVersion: Map<String, Map<String, WhatsNewContent>> = runCatching {
                gson.fromJson<Map<String, Map<String, WhatsNewContent>>>(json, type)
            }.getOrNull() ?: return null

            val versionEntry = byVersion[versionCode.toString()] ?: return null
            val localeKey = if (languageTag.startsWith("zh", ignoreCase = true)) "zh-TW" else "en"
            val content = versionEntry[localeKey] ?: versionEntry["en"] ?: return null

            // An entry with no usable text is treated as absent.
            if (content.title.isNullOrBlank() || content.highlights.isNullOrEmpty()) return null
            return content
        }
    }
}
