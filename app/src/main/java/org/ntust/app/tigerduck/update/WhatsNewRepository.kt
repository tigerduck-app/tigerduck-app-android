package org.ntust.app.tigerduck.update

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.update.WhatsNewRepository.Companion.parse
import org.ntust.app.tigerduck.update.WhatsNewRepository.Companion.select
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
        val json = readAsset() ?: return null
        return parse(json, versionCode, languageTag)
    }

    /**
     * The [WhatsNewContent] for the newest versionCode present in the asset,
     * ignoring the running build's versionCode. Backs the debug "Replay What's
     * new" action, which must preview the latest authored entry even on a build
     * whose versionCode predates it. Null if the asset is missing/malformed or
     * holds no usable entry.
     */
    fun latestEntry(languageTag: String): WhatsNewContent? {
        val json = readAsset() ?: return null
        return parseLatest(json, languageTag)
    }

    private fun readAsset(): String? = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }.getOrElse {
        Log.w(TAG, "whatsnew.json not readable", it)
        null
    }

    companion object {
        const val ASSET_NAME = "whatsnew.json"
        private const val TAG = "WhatsNewRepository"
        private val gson = Gson()

        /**
         * Pure parse step for [entryFor] — no Android dependencies,
         * unit-testable. See [select] for locale resolution.
         */
        fun parse(json: String, versionCode: Int, languageTag: String): WhatsNewContent? {
            val byVersion = deserialize(json) ?: return null
            return select(byVersion[versionCode.toString()], languageTag)
        }

        /**
         * Pure variant of [parse] for the highest versionCode present in the
         * asset — see [latestEntry]. Null if the asset is malformed or has no
         * numeric version key.
         */
        fun parseLatest(json: String, languageTag: String): WhatsNewContent? {
            val byVersion = deserialize(json) ?: return null
            val latestKey =
                byVersion.keys.mapNotNull(String::toIntOrNull).maxOrNull() ?: return null
            return select(byVersion[latestKey.toString()], languageTag)
        }

        private fun deserialize(json: String): Map<String, Map<String, WhatsNewContent>>? {
            val type = object : TypeToken<Map<String, Map<String, WhatsNewContent>>>() {}.type
            return runCatching {
                gson.fromJson<Map<String, Map<String, WhatsNewContent>>>(json, type)
            }.getOrNull()
        }

        /**
         * Picks the localized [WhatsNewContent] out of one version's per-locale
         * map. A Chinese [languageTag] (`zh-*`) maps to the `zh-TW` block;
         * everything else falls back to `en`. An entry with no usable text is
         * treated as absent.
         */
        private fun select(
            versionEntry: Map<String, WhatsNewContent>?,
            languageTag: String,
        ): WhatsNewContent? {
            versionEntry ?: return null
            val localeKey = if (languageTag.startsWith("zh", ignoreCase = true)) "zh-TW" else "en"
            val content = versionEntry[localeKey] ?: versionEntry["en"] ?: return null
            if (content.title.isNullOrBlank() || content.highlights.isNullOrEmpty()) return null
            return content
        }
    }
}
