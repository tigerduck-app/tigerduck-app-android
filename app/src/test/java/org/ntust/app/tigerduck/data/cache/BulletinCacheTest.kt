package org.ntust.app.tigerduck.data.cache

import android.app.Application
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The bulletin snapshot's clearing at sign-out, over real files. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class BulletinCacheTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    // A process death between a write's temporary file and its rename leaves that file.
    @Test
    fun `clearing deletes the snapshot and what a write cut short left behind`() = runTest {
        val cache = BulletinCache(context)
        cache.save(emptyList())
        cache.saveFetchedAt(1_800_000_000_000L)
        val dir = File(context.filesDir, "bulletins")
        File(dir, "summaries.json.tmp").writeText("[]")
        File(dir, "fetched_at.tmp").writeText("1")

        cache.clear()

        assertEquals(emptyList<String>(), dir.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    private fun bulletin(id: Int) = org.ntust.app.tigerduck.network.model.BulletinSummary(
        id = id, externalId = "e$id", title = "Bulletin $id", titleClean = null, canonicalOrg = null,
        importance = null, summary = null, sourceUrl = null, postedAt = null,
    )

    /** A clear started and not run yet when the process ends is run at the next start, before any read. */
    @Test
    fun `a clear the process did not live to run is run at the next start`() = runTest {
        val cache = BulletinCache(context)
        cache.save(listOf(bulletin(1)))
        // Never run: its scope's scheduler is not moved on, as a process ended first.
        cache.clearIn(kotlinx.coroutines.test.TestScope())

        val next = BulletinCache(context)

        assertEquals(emptyList<Int>(), next.load().map { it.id })
    }

    /**
     * Clears started one after another run in that order: a later one finishing first would let
     * a write through that the earlier one then removed.
     */
    @Test
    fun `a write waits for every clear started before it, and stays`() {
        val cache = BulletinCache(context)
        kotlinx.coroutines.runBlocking { cache.save(listOf(bulletin(9))) }
        val firstHeld = java.util.concurrent.CountDownLatch(1)
        val firstThread = java.util.concurrent.Executors.newSingleThreadExecutor().apply {
            execute { firstHeld.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        }
        cache.clearIn(kotlinx.coroutines.CoroutineScope(firstThread.asCoroutineDispatcher()))
        cache.clearIn(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))

        val saving = kotlin.concurrent.thread { kotlinx.coroutines.runBlocking { cache.save(listOf(bulletin(1))) } }
        Thread.sleep(300)
        org.junit.Assert.assertTrue("the write waits for the first clear", saving.isAlive)

        firstHeld.countDown()
        saving.join(5_000)

        assertEquals(listOf(1), kotlinx.coroutines.runBlocking { cache.load() }.map { it.id })
        firstThread.shutdown()
    }
}
