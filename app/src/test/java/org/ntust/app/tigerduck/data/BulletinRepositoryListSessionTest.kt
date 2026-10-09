package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulletinRepositoryListSessionTest {

    private val maxAge = 5 * 60_000L

    @Test
    fun `nothing is recent before the first refresh`() {
        assertNull(BulletinRepository().recentList(includeDeleted = false, nowMs = 1_000, maxAgeMs = maxAge))
    }

    @Test
    fun `a refresh stands inside the window and not after it`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = false, nextCursor = 42)

        assertEquals(42, repo.recentList(false, nowMs = 1_000 + maxAge - 1, maxAgeMs = maxAge)?.nextCursor)
        assertNull(repo.recentList(false, nowMs = 1_000 + maxAge, maxAgeMs = maxAge))
    }

    @Test
    fun `a refresh for the other filter does not count`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = true, nextCursor = 42)

        assertNull(repo.recentList(includeDeleted = false, nowMs = 2_000, maxAgeMs = maxAge))
    }

    @Test
    fun `the cursor follows the pages saved after the refresh`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = false, nextCursor = 42)
        repo.listAdvanced(includeDeleted = false, nextCursor = 7)

        val recent = repo.recentList(false, nowMs = 2_000, maxAgeMs = maxAge)
        assertEquals(7, recent?.nextCursor)
        assertEquals("advancing is not a refresh", 1_000L, recent?.fetchedAtMs)
    }

    @Test
    fun `pages for the other filter leave the cursor alone`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 1_000, includeDeleted = false, nextCursor = 42)
        repo.listAdvanced(includeDeleted = true, nextCursor = 7)

        assertEquals(42, repo.recentList(false, nowMs = 2_000, maxAgeMs = maxAge)?.nextCursor)
    }

    @Test
    fun `a clock that went backwards does not keep the list forever`() {
        val repo = BulletinRepository()
        repo.listRefreshed(nowMs = 10_000, includeDeleted = false, nextCursor = null)

        assertNull(repo.recentList(false, nowMs = 5_000, maxAgeMs = maxAge))
    }
}
