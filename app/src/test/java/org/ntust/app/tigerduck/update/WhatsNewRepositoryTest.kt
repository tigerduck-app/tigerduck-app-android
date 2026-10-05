package org.ntust.app.tigerduck.update

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.WhatsNewContent
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import java.io.File

class WhatsNewRepositoryTest {

    private val json = """
        {
          "21": {
            "zh-Hant": { "title": "1.5.0 新功能", "highlights": ["一", "二"] },
            "en":    { "title": "What's new in 1.5.0", "highlights": ["One", "Two"] }
          }
        }
    """.trimIndent()

    private fun bodies(json: String, versionCode: Int, languageTag: String) =
        WhatsNewRepository.parse(json, versionCode, languageTag)?.items?.map { it.body }

    @Test
    fun `returns the english entry for an english language tag`() {
        val summary = WhatsNewRepository.parse(json, versionCode = 21, languageTag = "en-US")
        assertEquals("What's new in 1.5.0", summary?.title)
        assertEquals(21, summary?.versionCode)
        assertEquals(listOf("One", "Two"), bodies(json, 21, "en-US"))
    }

    @Test
    fun `returns the zh-Hant entry for a chinese language tag`() {
        assertEquals(listOf("一", "二"), bodies(json, 21, "zh-Hant-TW"))
    }

    @Test
    fun `returns the zh-Hant entry for cantonese`() {
        // `yue-HK` ships as a selectable UI language; a `zh` string prefix
        // test missed it and handed Cantonese users the English release notes.
        assertEquals(listOf("一", "二"), bodies(json, 21, "yue-HK"))
    }

    @Test
    fun `every shipped chinese locale gets the zh-Hant entry`() {
        listOf("zh-TW", "zh-CN", "zh-HK", "zh-MO", "zh-SG", "yue-HK").forEach { tag ->
            val summary = WhatsNewRepository.parse(json, versionCode = 21, languageTag = tag)
            assertEquals(tag, "1.5.0 新功能", summary?.title)
        }
    }

    @Test
    fun `falls back to english for a non-chinese non-english language tag`() {
        val summary = WhatsNewRepository.parse(json, versionCode = 21, languageTag = "ja-JP")
        assertEquals("What's new in 1.5.0", summary?.title)
    }

    @Test
    fun `returns null when no entry exists for the version`() {
        assertNull(WhatsNewRepository.parse(json, versionCode = 99, languageTag = "en-US"))
    }

    @Test
    fun `returns null for malformed json`() {
        assertNull(WhatsNewRepository.parse("{ not json", versionCode = 21, languageTag = "en-US"))
        assertTrue(WhatsNewRepository.parseAll("{ not json", languageTag = "en-US").isEmpty())
    }

    @Test
    fun `a malformed version entry drops only that version`() {
        val mixed = """
            {
              "27": { "en": { "title": "Good", "highlights": ["One"] } },
              "28": { "en": { "title": "Broken", "items": { "title": "not a list" } } }
            }
        """.trimIndent()
        val summaries = WhatsNewRepository.parseAll(mixed, languageTag = "en-US")
        assertEquals(setOf(27), summaries.keys)
        assertEquals("Good", summaries[27]?.title)
    }

    @Test
    fun `a json root that is not an object is empty`() {
        assertTrue(WhatsNewRepository.parseAll("[1, 2]", languageTag = "en-US").isEmpty())
    }

    /**
     * The runtime parse forgives a malformed entry by dropping it, which
     * would let a broken release note ship silently. This decodes the shipped
     * asset strictly instead, so the mistake fails the build.
     */
    @Test
    fun `the shipped whatsnew json decodes strictly and every version has a summary in both languages`() {
        val json = File("src/main/assets/whatsnew.json").readText()
        val type = object : TypeToken<Map<String, Map<String, WhatsNewContent>>>() {}.type
        val strict: Map<String, Map<String, WhatsNewContent>> = Gson().fromJson(json, type)
        assertNotNull(strict)
        val versions = strict.keys.map { key ->
            key.toIntOrNull() ?: throw AssertionError("version key \"$key\" is not a versionCode")
        }.toSet()
        assertEquals(versions, WhatsNewRepository.parseAll(json, languageTag = "en").keys)
        assertEquals(versions, WhatsNewRepository.parseAll(json, languageTag = "zh-Hant-TW").keys)
    }

    @Test
    fun `returns null when an entry has blank title or empty highlights`() {
        val blank = """
            { "21": { "en": { "title": "", "highlights": [] } } }
        """.trimIndent()
        assertNull(WhatsNewRepository.parse(blank, versionCode = 21, languageTag = "en-US"))
    }

    @Test
    fun `legacy highlights become body-only rows`() {
        val summary = WhatsNewRepository.parse(json, versionCode = 21, languageTag = "en-US")
        assertEquals(
            listOf(
                WhatsNewSummaryItem(title = null, body = "One", icon = null),
                WhatsNewSummaryItem(title = null, body = "Two", icon = null),
            ),
            summary?.items,
        )
    }

    @Test
    fun `decodes items with title body and icon`() {
        val itemsJson = """
            {
              "28": {
                "en": {
                  "title": "What's new in 2.3.0",
                  "items": [
                    { "icon": "mail", "title": "School Mail", "body": "Send and receive." },
                    { "title": "Headline only" },
                    { "body": "Body only" }
                  ]
                }
              }
            }
        """.trimIndent()
        val summary = WhatsNewRepository.parse(itemsJson, versionCode = 28, languageTag = "en-US")
        assertEquals(
            listOf(
                WhatsNewSummaryItem(title = "School Mail", body = "Send and receive.", icon = "mail"),
                WhatsNewSummaryItem(title = "Headline only", body = null, icon = null),
                WhatsNewSummaryItem(title = null, body = "Body only", icon = null),
            ),
            summary?.items,
        )
    }

    @Test
    fun `items win over highlights when both are present`() {
        val both = """
            { "28": { "en": { "title": "T", "items": [ { "title": "Item" } ], "highlights": ["Old"] } } }
        """.trimIndent()
        assertEquals(
            listOf("Item"),
            WhatsNewRepository.parse(both, 28, "en-US")?.items?.map { it.title },
        )
    }

    @Test
    fun `blank items fall back to highlights`() {
        val blankItems = """
            { "28": { "en": { "title": "T", "items": [ { "title": " ", "body": "" }, null ], "highlights": ["Old"] } } }
        """.trimIndent()
        assertEquals(listOf("Old"), bodies(blankItems, 28, "en-US"))
    }

    @Test
    fun `an entry with only blank items and no highlights is absent`() {
        val blankItems = """
            { "28": { "en": { "title": "T", "items": [ { "title": "" } ] } } }
        """.trimIndent()
        assertNull(WhatsNewRepository.parse(blankItems, 28, "en-US"))
    }

    @Test
    fun `parseAll keys usable summaries by numeric version and drops the rest`() {
        val multi = """
            {
              "9":  { "en": { "title": "Old", "highlights": ["a"] } },
              "10": { "en": { "title": "New", "highlights": ["b"] } },
              "11": { "en": { "title": "", "highlights": ["c"] } },
              "draft": { "en": { "title": "Draft", "highlights": ["d"] } }
            }
        """.trimIndent()
        val all = WhatsNewRepository.parseAll(multi, languageTag = "en-US")
        assertEquals(setOf(9, 10), all.keys)
        assertEquals("New", all[10]?.title)
    }

    @Test
    fun `parseAll respects locale selection`() {
        val all = WhatsNewRepository.parseAll(json, languageTag = "zh-Hant-TW")
        assertEquals("1.5.0 新功能", all[21]?.title)
    }

    @Test
    fun `parseAll is empty when no version entry exists`() {
        assertTrue(WhatsNewRepository.parseAll("{}", languageTag = "en-US").isEmpty())
    }
}
