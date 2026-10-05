package org.ntust.app.tigerduck.update

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.model.WhatsNewContent
import org.ntust.app.tigerduck.data.model.WhatsNewItemContent
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.update.WhatsNewRepository.Companion.parseAll
import org.ntust.app.tigerduck.update.WhatsNewRepository.Companion.select
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the maintainer-authored summary page ("What's new in X") from
 * `assets/whatsnew.json`. The feature pages shown before it live in code, in
 * `WhatsNewCatalog`.
 *
 * The JSON is an object keyed by versionCode string; each version holds a
 * per-locale map (`zh-Hant`, `en`) of [WhatsNewContent].
 */
@Singleton
class WhatsNewRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Every usable summary in the locale implied by [languageTag], keyed by
     * versionCode. Empty if the asset is missing or malformed.
     */
    fun summaries(languageTag: String): Map<Int, WhatsNewSummary> {
        val json = readAsset() ?: return emptyMap()
        return parseAll(json, languageTag)
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
         * Pure parse step for [summaries] — no Android dependencies,
         * unit-testable. A version whose key isn't a number, whose entry
         * doesn't decode, or whose entry has no usable text in the resolved
         * locale, is left out. See [select] for locale resolution.
         */
        fun parseAll(json: String, languageTag: String): Map<Int, WhatsNewSummary> {
            val byVersion = deserialize(json) ?: return emptyMap()
            return buildMap {
                for ((key, versionEntry) in byVersion) {
                    val versionCode = key.toIntOrNull() ?: continue
                    select(versionEntry, versionCode, languageTag)?.let { put(versionCode, it) }
                }
            }
        }

        /** [parseAll] narrowed to one version. */
        fun parse(json: String, versionCode: Int, languageTag: String): WhatsNewSummary? =
            parseAll(json, languageTag)[versionCode]

        /**
         * Decodes each version on its own, so one malformed entry (say
         * `items` written as an object) drops only that version instead of
         * every summary in the file. Null only when the file isn't a JSON
         * object at all.
         */
        private fun deserialize(json: String): Map<String, Map<String, WhatsNewContent>>? {
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return null
            val type = object : TypeToken<Map<String, WhatsNewContent>>() {}.type
            return buildMap {
                for ((key, value) in root.entrySet()) {
                    runCatching { gson.fromJson<Map<String, WhatsNewContent>?>(value, type) }
                        .getOrNull()
                        ?.let { put(key, it) }
                }
            }
        }

        /**
         * Picks the localized [WhatsNewContent] out of one version's per-locale
         * map and resolves it into a [WhatsNewSummary]. Any Chinese
         * [languageTag] — `zh-*` and Cantonese `yue-HK` alike, per
         * [AppLanguageManager.isChineseLanguageTag] — maps to the `zh-Hant`
         * block; everything else falls back to `en`. An entry with a blank
         * title or no usable row is treated as absent.
         */
        private fun select(
            versionEntry: Map<String, WhatsNewContent>?,
            versionCode: Int,
            languageTag: String,
        ): WhatsNewSummary? {
            versionEntry ?: return null
            val localeKey =
                if (AppLanguageManager.isChineseLanguageTag(languageTag)) "zh-Hant" else "en"
            val content = versionEntry[localeKey] ?: versionEntry["en"] ?: return null
            val title = content.title.trimToNull() ?: return null
            val items = resolveItems(content)
            if (items.isEmpty()) return null
            return WhatsNewSummary(versionCode = versionCode, title = title, items = items)
        }

        /**
         * [WhatsNewContent.items] when it has a usable row, otherwise the
         * legacy [WhatsNewContent.highlights] as body-only rows. Gson fills a
         * stray `null` array element with null despite the non-null element
         * type, hence the nullable lambda parameters.
         */
        private fun resolveItems(content: WhatsNewContent): List<WhatsNewSummaryItem> {
            val items = content.items.orEmpty().mapNotNull { item: Any? ->
                val row = item as? WhatsNewItemContent ?: return@mapNotNull null
                val title = row.title.trimToNull()
                val body = row.body.trimToNull()
                if (title == null && body == null) return@mapNotNull null
                WhatsNewSummaryItem(title = title, body = body, icon = row.icon.trimToNull())
            }
            if (items.isNotEmpty()) return items
            return content.highlights.orEmpty()
                .mapNotNull { line: String? -> line.trimToNull() }
                .map { WhatsNewSummaryItem(title = null, body = it, icon = null) }
        }

        private fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
