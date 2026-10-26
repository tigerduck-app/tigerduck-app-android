package org.ntust.app.tigerduck.ui.screen.score

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.ScoreReport
import java.io.IOException
import java.util.Date

/** The Score page's "Last synced" row: the date the score cache keeps with each student's grades. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreSyncedAtTest {

    private val signedIn = MutableStateFlow(true)

    /** The score cache, by student ID. */
    private val cached = mutableMapOf<String, DataCache.ScoreReportSnapshot>()

    private fun TestScope.syncedAt() = ScoreSyncedAt(backgroundScope, signedIn) { cached[it] }

    private fun snapshot(atMs: Long) = DataCache.ScoreReportSnapshot(ScoreReport.EMPTY, Date(atMs))

    @Test
    fun `the page opens on the cached grades' date, or on never without one`() = runTest {
        val syncedAt = syncedAt()

        syncedAt.show(snapshot(5_000))
        assertEquals(5_000L, syncedAt.value.value)

        syncedAt.show(null)
        assertEquals(0L, syncedAt.value.value)
        // A snapshot an older build wrote without the date.
        syncedAt.show(DataCache.ScoreReportSnapshot(ScoreReport.EMPTY, null))
        assertEquals(0L, syncedAt.value.value)
    }

    @Test
    fun `a refresh that lands dates the grades by the fetch, or keeps the date of the cache that answered`() = runTest {
        cached["B1"] = snapshot(5_000)
        val syncedAt = syncedAt()
        syncedAt.show(cached["B1"])

        // A fetch from the server writes a new snapshot.
        syncedAt.afterRefresh("B1") { cached["B1"] = snapshot(9_000) }
        assertEquals(9_000L, syncedAt.value.value)

        // One the cache answered within its lifetime writes nothing.
        syncedAt.afterRefresh("B1") { }
        assertEquals(9_000L, syncedAt.value.value)
    }

    @Test
    fun `a refresh that fails leaves the date where it was`() = runTest {
        cached["B1"] = snapshot(5_000)
        val syncedAt = syncedAt()
        syncedAt.show(cached["B1"])

        val thrown = runCatching { syncedAt.afterRefresh<Unit>("B1") { throw IOException("offline") } }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals(5_000L, syncedAt.value.value)
    }

    @Test
    fun `signing out forgets the date`() = runTest {
        val syncedAt = syncedAt()
        syncedAt.show(snapshot(5_000))
        runCurrent()
        assertEquals(5_000L, syncedAt.value.value)

        signedIn.value = false
        runCurrent()

        assertEquals(0L, syncedAt.value.value)
    }
}
