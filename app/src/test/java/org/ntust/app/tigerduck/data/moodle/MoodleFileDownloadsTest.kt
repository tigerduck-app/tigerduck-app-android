package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.ui.screen.mail.HeldDispatcher
import java.io.File

/** The downloaded Moodle files' store on its own: a file's state, and what is left on the device. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleFileDownloadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val location get() = File(tmp.root, "acct/5/m1/ab/1/notes.pdf")

    @Test
    fun `a download stopped and tapped again keeps showing the new one's progress after the old one ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
            val firstStarted = CompletableDeferred<Unit>()
            val firstEnds = CompletableDeferred<Unit>()
            val first = async {
                downloads.download(location, fetch = { _, _ ->
                    firstStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (e: CancellationException) {
                        // A stopped request can take a moment to wind down.
                        withContext(NonCancellable) { firstEnds.await() }
                        throw e
                    }
                }, isCurrent = { true })
            }
            firstStarted.await()
            downloads.cancel(location)

            val second = async {
                downloads.download(location, fetch = { _, progress ->
                    progress(0.4f)
                    CompletableDeferred<Unit>().await()
                }, isCurrent = { true })
            }
            runCurrent()
            firstEnds.complete(Unit)
            runCurrent()

            assertNull(first.await())
            assertEquals(MoodleDownloadState.Downloading(0.4f), downloads.state(location))
            second.cancel()
        }

    @Test
    fun `a download stopped while its partial file is being made leaves nothing behind`() = runTest {
        // The disk's work waits here until the test runs it, so the stop can land in between.
        val disk = ArrayDeque<Runnable>()
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                disk += block
            }
        }
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = io)
        var fetched = false
        val opening = async { downloads.download(location, fetch = { _, _ -> fetched = true }, isCurrent = { true }) }
        runCurrent()
        disk.removeFirst().run() // is there a copy already
        runCurrent()
        disk.removeFirst().run() // the partial file is made, and the stop lands before its maker hears
        assertTrue(location.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })

        downloads.cancel(location)
        runCurrent()
        while (disk.isNotEmpty()) {
            disk.removeFirst().run()
            runCurrent()
        }

        assertNull(opening.await())
        assertTrue(!fetched)
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `partial files a download left when the app was ended are swept, and do not count`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
        val course = File(tmp.root, "acct/5")
        val stale = File(course, "m1/ab/1/.dl123.part").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(700))
        }
        val elsewhere = File(tmp.root, "acct/6/m2/cd/1/.dl456.part").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(300))
        }
        val kept = File(course, "m1/ef/1/notes.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(100))
        }

        downloads.index(course)
        assertTrue(!stale.exists())
        assertEquals(setOf(kept.path), downloads.snapshot.value.copies)

        assertEquals("the settings' measure sweeps every course's", 100L, downloads.measure())
        assertTrue(!elsewhere.exists())
    }

    @Test
    fun `the partial file of a download on its way is not swept`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val opening = async {
            downloads.download(location, fetch = { target, _ ->
                target.writeText("half")
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }, isCurrent = { true })
        }
        started.await()

        downloads.index(File(tmp.root, "acct/5"))
        downloads.measure()

        assertTrue(location.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
        opening.cancel()
    }

    @Test
    fun `clearing waits for the stopped downloads to wind down before it wipes the files`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val wound = CompletableDeferred<Unit>()
        val opening = async {
            downloads.download(location, fetch = { target, _ ->
                target.writeText("half")
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (e: CancellationException) {
                    // A stopped request can take a moment to wind down.
                    withContext(NonCancellable) { wound.await() }
                    throw e
                }
            }, isCurrent = { true })
        }
        started.await()

        val clearing = async { downloads.clear() }
        runCurrent()

        assertFalse("wiped while a stopped download was still winding down", clearing.isCompleted)
        wound.complete(Unit)
        assertEquals(0L, clearing.await())
        assertNull(opening.await())
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `a download started while the folder is being swept keeps the file it is writing`() = runTest(UnconfinedTestDispatcher()) {
        // Every step on the disk runs where it is called, so the download started below is as far
        // along by the time the listing returns as one started on another thread would be.
        val direct = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
        }
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = direct)
        val course = File(tmp.root, "acct/5")
        val stale = File(course, "m1/cd/1/.dl9.part").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        val ends = CompletableDeferred<Unit>()
        var opening: Deferred<File?>? = null
        var writingWhenListed = false
        // Listing the folder is where the sweep stands between its look at the downloads on their
        // way and its deleting: a download started from there is one started on another thread.
        val listed = object : File(course.path) {
            override fun listFiles(): Array<File>? {
                if (opening == null) {
                    opening = async(start = CoroutineStart.UNDISPATCHED) {
                        downloads.download(location, fetch = { target, _ ->
                            target.outputStream().use { out ->
                                out.write(1)
                                ends.await()
                                out.write(2)
                            }
                        }, isCurrent = { true })
                    }
                    writingWhenListed = location.parentFile?.listFiles()?.any { it.name.endsWith(".part") } == true
                }
                return super.listFiles()
            }
        }

        downloads.index(listed)

        assertTrue("the download had made its file by the time the folder was listed", writingWhenListed)
        assertFalse(stale.exists())
        assertTrue(location.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
        ends.complete(Unit)
        assertEquals(location, opening!!.await())
        assertEquals(MoodleDownloadState.Downloaded(location), downloads.state(location))
    }

    @Test
    fun `a downloaded file named like a partial file is neither swept nor hidden`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
        val course = File(tmp.root, "acct/5")
        // Moodle lists it by that name. A partial file's own name begins with a dot, which a
        // downloaded file's cannot.
        val dataset = File(course, "m1/ab/1/dataset.part").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(100))
        }
        assertEquals("dl7.part", MoodleFileDownloads.safeFileName(".dl7.part"))

        downloads.index(course)
        assertEquals(setOf(dataset.path), downloads.snapshot.value.copies)
        assertEquals(100L, downloads.measure())
        assertTrue(dataset.exists())
    }

    @Test
    fun `a second open that found no copy just before the first download kept it does not download it again`() =
        runTest(UnconfinedTestDispatcher()) {
            // The disk and the second open's thread run only when told: the second open is held
            // between its look for the copy and its look for a download to join, as a busy thread
            // can hold it, while the first download keeps its copy and winds down.
            val disk = HeldDispatcher()
            val secondThread = HeldDispatcher()
            val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = disk)
            val gate = CompletableDeferred<Unit>()
            var fetches = 0
            val fetch: suspend (File, (Float) -> Unit) -> Unit = { target, _ ->
                fetches++
                target.writeText("pdf bytes")
                gate.await()
            }
            val first = async { downloads.download(location, fetch, isCurrent = { true }) }
            disk.drain()
            val second = async(secondThread) { downloads.download(location, fetch, isCurrent = { true }) }
            secondThread.drain()
            assertEquals(1, disk.pending)
            disk.runNext()

            gate.complete(Unit)
            disk.drain()
            assertTrue(first.isCompleted)
            while (disk.pending + secondThread.pending > 0) {
                secondThread.drain()
                disk.drain()
            }

            assertEquals(location, first.await())
            assertEquals(location, second.await())
            assertEquals(1, fetches)
        }
}
