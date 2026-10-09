package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulletinRepositoryListSessionTest {

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
}
