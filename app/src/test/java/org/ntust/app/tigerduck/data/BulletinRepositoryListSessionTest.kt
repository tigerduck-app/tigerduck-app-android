package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulletinRepositoryListSessionTest {

    @Test
    fun `a detail's own summary can be cleared when no list row has one`() {
        val repo = BulletinRepository()
        fun detail(summary: String?) = org.ntust.app.tigerduck.network.model.BulletinDetail(
            id = 8, externalId = "e8", title = "T", titleClean = null, canonicalOrg = null,
            importance = null, summary = summary, sourceUrl = null, postedAt = null,
            bodyClean = null, bodyMd = null, rawPublisher = null,
        )
        repo.putDetail(detail("old"))
        repo.putDetail(detail(null))

        org.junit.Assert.assertNull(repo.summary(8)?.summary)
    }

    @Test
    fun `a detail without a summary keeps the list row's`() {
        val repo = BulletinRepository()
        repo.putSummaries(listOf(org.ntust.app.tigerduck.network.model.BulletinSummary(
            id = 7, externalId = "e7", title = "T", titleClean = null, canonicalOrg = null,
            importance = null, summary = "列表摘要", sourceUrl = null, postedAt = null,
        )))
        repo.putDetail(org.ntust.app.tigerduck.network.model.BulletinDetail(
            id = 7, externalId = "e7", title = "T", titleClean = null, canonicalOrg = null,
            importance = null, summary = "  ", sourceUrl = null, postedAt = null,
            bodyClean = null, bodyMd = null, rawPublisher = null,
        ))

        // The next open of the detail falls back to it when the detail has no body.
        org.junit.Assert.assertEquals("列表摘要", repo.summary(7)?.summary)
    }

    @Test
    fun `there is no session before the first refresh`() {
        assertNull(BulletinRepository().listSession(includeDeleted = false))
    }

    @Test
    fun `a refresh is remembered with its time and cursor`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = false, nextCursor = 42)

        val session = repo.listSession(includeDeleted = false)
        assertEquals(1_000L, session?.fetchedAtMs)
        assertEquals(42, session?.nextCursor)
    }

    @Test
    fun `a refresh for the other filter does not count`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = true, nextCursor = 42)

        assertNull(repo.listSession(includeDeleted = false))
    }

    @Test
    fun `the cursor follows the pages saved after the refresh`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = false, nextCursor = 42)
        repo.listAdvanced(includeDeleted = false, nextCursor = 7)

        val session = repo.listSession(includeDeleted = false)
        assertEquals(7, session?.nextCursor)
        assertEquals("advancing is not a refresh", 1_000L, session?.fetchedAtMs)
    }

    @Test
    fun `pages for the other filter leave the cursor alone`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = false, nextCursor = 42)
        repo.listAdvanced(includeDeleted = true, nextCursor = 7)

        assertEquals(42, repo.listSession(includeDeleted = false)?.nextCursor)
    }

    /** An endpoint move lets go of everything the old backend's answers left here (#592). */
    @Test
    fun `forget lets go of the summaries, details, list session and taxonomy`() {
        val repo = BulletinRepository()
        repo.putSummaries(listOf(org.ntust.app.tigerduck.network.model.BulletinSummary(
            id = 7, externalId = "e7", title = "T", titleClean = null, canonicalOrg = null,
            importance = null, summary = "列表摘要", sourceUrl = null, postedAt = null,
        )))
        repo.putDetail(org.ntust.app.tigerduck.network.model.BulletinDetail(
            id = 8, externalId = "e8", title = "T", titleClean = null, canonicalOrg = null,
            importance = null, summary = null, sourceUrl = null, postedAt = null,
            bodyClean = null, bodyMd = null, rawPublisher = null,
        ))
        repo.listRefreshed(nowMs = 1_000L, includeDeleted = false, nextCursor = 3)
        kotlinx.coroutines.runBlocking {
            repo.getOrFetchTaxonomy { org.ntust.app.tigerduck.network.model.TaxonomyResponse(emptyList(), emptyList(), emptyList()) }
        }

        repo.forget()

        assertNull(repo.summary(7))
        assertNull(repo.summary(8))
        assertNull(repo.detail(8))
        assertNull(repo.listSession(includeDeleted = false))
        assertNull(repo.taxonomy())
    }
}
