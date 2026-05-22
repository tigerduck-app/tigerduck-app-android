package org.ntust.app.tigerduck.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewRepositoryTest {

    private val json = """
        {
          "21": {
            "zh-TW": { "title": "1.5.0 新功能", "highlights": ["一", "二"] },
            "en":    { "title": "What's new in 1.5.0", "highlights": ["One", "Two"] }
          }
        }
    """.trimIndent()

    @Test
    fun `returns the english entry for an english language tag`() {
        val content = WhatsNewRepository.parse(json, versionCode = 21, languageTag = "en-US")
        assertEquals("What's new in 1.5.0", content?.title)
        assertEquals(listOf("One", "Two"), content?.highlights)
    }

    @Test
    fun `returns the zh-TW entry for a chinese language tag`() {
        val content = WhatsNewRepository.parse(json, versionCode = 21, languageTag = "zh-Hant-TW")
        assertEquals(listOf("一", "二"), content?.highlights)
    }

    @Test
    fun `falls back to english for a non-chinese non-english language tag`() {
        val content = WhatsNewRepository.parse(json, versionCode = 21, languageTag = "ja-JP")
        assertEquals("What's new in 1.5.0", content?.title)
    }

    @Test
    fun `returns null when no entry exists for the version`() {
        assertNull(WhatsNewRepository.parse(json, versionCode = 99, languageTag = "en-US"))
    }

    @Test
    fun `returns null for malformed json`() {
        assertNull(WhatsNewRepository.parse("{ not json", versionCode = 21, languageTag = "en-US"))
    }

    @Test
    fun `returns null when an entry has blank title or empty highlights`() {
        val blank = """
            { "21": { "en": { "title": "", "highlights": [] } } }
        """.trimIndent()
        assertNull(WhatsNewRepository.parse(blank, versionCode = 21, languageTag = "en-US"))
    }

    @Test
    fun `parseLatest returns the entry for the only version present`() {
        val content = WhatsNewRepository.parseLatest(json, languageTag = "en-US")
        assertEquals("What's new in 1.5.0", content?.title)
    }

    @Test
    fun `parseLatest picks the highest version by numeric value, not lexically`() {
        // Lexically "9" > "10"; numerically 10 wins.
        val multi = """
            {
              "9":  { "en": { "title": "Old", "highlights": ["a"] } },
              "10": { "en": { "title": "New", "highlights": ["b"] } }
            }
        """.trimIndent()
        val content = WhatsNewRepository.parseLatest(multi, languageTag = "en-US")
        assertEquals("New", content?.title)
    }

    @Test
    fun `parseLatest respects locale selection`() {
        val content = WhatsNewRepository.parseLatest(json, languageTag = "zh-Hant-TW")
        assertEquals(listOf("一", "二"), content?.highlights)
    }

    @Test
    fun `parseLatest returns null for malformed json`() {
        assertNull(WhatsNewRepository.parseLatest("{ not json", languageTag = "en-US"))
    }

    @Test
    fun `parseLatest returns null when no version entry exists`() {
        assertNull(WhatsNewRepository.parseLatest("{}", languageTag = "en-US"))
    }
}
