package org.ntust.app.tigerduck.data.cache

import android.app.Application
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
}
