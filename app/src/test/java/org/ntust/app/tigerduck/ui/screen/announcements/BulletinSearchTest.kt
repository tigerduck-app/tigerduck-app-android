package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.BulletinSummary

class BulletinSearchTest {
    private fun bulletin(title: String, titleClean: String?, summary: String? = null) = BulletinSummary(
        id = 1, externalId = "x", title = title, titleClean = titleClean, canonicalOrg = null,
        importance = null, summary = summary, sourceUrl = null, postedAt = null,
    )

    @Test
    fun `a search finds a bulletin by its original title as well as the cleaned-up one`() {
        val b = bulletin(title = "【公告】114學年度獎學金申請", titleClean = "獎學金申請")
        assertTrue(b.matchesSearch("獎學金"))
        assertTrue(b.matchesSearch("【公告】"))
        assertTrue(b.matchesSearch("114學年度"))
        assertFalse(b.matchesSearch("停課"))
    }

    @Test
    fun `a search reads the summary and ignores case`() {
        assertTrue(bulletin("Notice", null, summary = "Library Hours").matchesSearch("library"))
    }

    @Test
    fun `the search resets only after more than five minutes away`() {
        assertFalse(bulletinSearchResets(awayMs = 5 * 60_000L))
        assertTrue(bulletinSearchResets(awayMs = 5 * 60_000L + 1))
    }
}
