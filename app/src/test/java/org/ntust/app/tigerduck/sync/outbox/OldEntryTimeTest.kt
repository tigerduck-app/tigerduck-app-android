package org.ntust.app.tigerduck.sync.outbox

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The old-entry time (X1): an edit an older build queued with no time is given the earliest moment
 * it is known to have existed, `min(its file's time, this build's first run)`, else 0; filled as
 * the lane first reads it, and written back before anything else writes the file.
 */
class OldEntryTimeTest {

    private val prefs = JvmContext().getSharedPreferences("tigerduck_outbox", 0)
    private var now = 5_000L
    // Inline, so a test's virtual time sees every step; the test below of where the disk work runs gives its own.
    private val times = OldEntryTime(prefs, Dispatchers.Unconfined) { now }
    private val file: File = File.createTempFile("unsent_marks", ".json")
    private val series = SignInSeries()

    @After
    fun tearDown() {
        file.delete()
    }

    /** The queue file a lane keeps its entries in, as DataCache writes it: each save moves its time to now. */
    private inner class FileStore(initial: List<Edit>) : LaneStore<Edit> {
        var entries = initial
        val saves = mutableListOf<List<Edit>>()

        /** Each write asked for, with its sign-in, whether the disk took it or not. */
        val asked = mutableListOf<SignIn>()

        /** A full disk: nothing is written. */
        var full = false

        override suspend fun load(): List<Edit> = entries

        override suspend fun save(entries: List<Edit>, signIn: SignIn): Boolean {
            asked += signIn
            if (full) return false
            saves += entries
            this.entries = entries
            file.writeText(entries.toString())
            file.setLastModified(now)
            return true
        }
    }

    private fun TestScope.lane(store: FileStore, file: File? = this@OldEntryTimeTest.file) = OutboxLane(
        OutboxChannelId.MARKS,
        store,
        LanePolicy(
            Edit::field, 5, onDisk(),
            untimed = UntimedEntries({ it.madeAtMs }, { e, t -> e.copy(madeAtMs = t) }, { times.oldEntryTimeMs(file) }),
        ),
        AckClock(),
        backgroundScope,
    ) { _, _ -> SendResult.Sent(null) }

    @Test
    fun `an entry with no time in a file an older build wrote before this one first ran takes the file's time`() = runTest {
        file.setLastModified(1_000)
        times.recordFirstRun()
        now = 9_000
        val store = FileStore(listOf(Edit("f1", "a", madeAtMs = null), Edit("f2", "b", madeAtMs = 4_000)))

        assertEquals(listOf(1_000L, 4_000L), lane(store).entries().map { it.madeAtMs })
    }

    @Test
    fun `an entry with no time in a file written since this build first ran takes the first run`() = runTest {
        times.recordFirstRun()
        file.setLastModified(7_000)
        now = 9_000
        val store = FileStore(listOf(Edit("f1", "a", madeAtMs = null)))

        assertEquals(5_000L, lane(store).entries().single().madeAtMs)
    }

    @Test
    fun `a store whose file other edits write takes the first run`() = runTest {
        file.setLastModified(1_000)
        times.recordFirstRun()
        now = 9_000

        assertEquals(5_000L, lane(FileStore(listOf(Edit("f1", "a", madeAtMs = null))), file = null).entries().single().madeAtMs)
    }

    @Test
    fun `with neither time known, the oldest stamp there is`() = runTest {
        file.delete()

        assertNull(times.firstRunMs)
        assertEquals(0L, times.oldEntryTimeMs(file))
        assertEquals(0L, times.oldEntryTimeMs(null))
        assertEquals(0L, lane(FileStore(listOf(Edit("f1", "a", madeAtMs = null)))).entries().single().madeAtMs)
    }

    @Test
    fun `the time filled is written back before anything else writes the file, and survives the saves after`() = runTest {
        file.setLastModified(1_000)
        times.recordFirstRun()
        now = 9_000
        val store = FileStore(listOf(Edit("f1", "a", madeAtMs = null)))
        val lane = lane(store)
        val signIn = series.current

        lane.enqueue(Edit("f2", "b", madeAtMs = 8_000), signIn)

        assertEquals(
            listOf(
                listOf(Edit("f1", "a", madeAtMs = 1_000)),
                listOf(Edit("f1", "a", madeAtMs = 1_000), Edit("f2", "b", madeAtMs = 8_000)),
            ),
            store.saves,
        )
        // The file is this build's now, and newer than its first run; the time stays the one filled.
        now = 12_000
        lane.enqueue(Edit("f3", "c", madeAtMs = 11_000), signIn)
        assertEquals(1_000L, store.entries.first().madeAtMs)
        assertEquals(listOf(1_000L, 8_000L, 11_000L), OutboxLane(
            OutboxChannelId.MARKS, store, LanePolicy(Edit::field, 5, onDisk()), AckClock(), backgroundScope,
        ) { _, _ -> SendResult.Sent(null) }.entries().map { it.madeAtMs })
    }

    @Test
    fun `an edit sent goes with the time filled`() = runTest {
        file.setLastModified(1_000)
        times.recordFirstRun()
        val store = FileStore(listOf(Edit("f1", "a", madeAtMs = null)))
        val sent = mutableListOf<Long?>()
        val lane = OutboxLane(
            OutboxChannelId.MARKS,
            store,
            LanePolicy(Edit::field, 5, onDisk(), untimed = UntimedEntries({ it.madeAtMs }, { e, t -> e.copy(madeAtMs = t) }, { times.oldEntryTimeMs(file) })),
            AckClock(),
            backgroundScope,
        ) { edit, _ ->
            sent += edit.madeAtMs
            SendResult.Sent(null)
        }

        lane.drain(series.current)

        assertEquals(listOf<Long?>(1_000L), sent)
    }

    @Test
    fun `under an ended sign-in the time is given but not written`() = runTest {
        file.setLastModified(1_000)
        // Written nowhere, the time is given again at each read.
        val store = FileStore(listOf(Edit("f1", "a", madeAtMs = null), Edit("f2", "b", madeAtMs = null))).apply { full = true }
        val backend = FakeBackend()
        val lane = OutboxLane(
            OutboxChannelId.MARKS,
            store,
            LanePolicy(Edit::field, 5, onDisk(), untimed = UntimedEntries({ it.madeAtMs }, { e, t -> e.copy(madeAtMs = t) }, { times.oldEntryTimeMs(file) })),
            AckClock(),
            backgroundScope,
            backend::send,
        )
        val signIn = series.current
        val first = launch { lane.drain(signIn, fields = setOf("f1")) }
        runCurrent()
        val second = launch { lane.drain(signIn, fields = setOf("f2")) }
        runCurrent()
        assertEquals(listOf(signIn), store.asked)

        // The second read comes after the sign-in ended.
        series.end()
        backend.reply(SendResult.Sent(null))
        first.join()
        second.join()

        assertEquals("nothing written for an ended sign-in", listOf(signIn), store.asked)
        assertEquals(listOf<Long?>(1_000L), backend.sent.map { it.first.madeAtMs })
        assertEquals(1_000L, lane.entries().last().madeAtMs)
    }

    @Test
    fun `the first run is recorded once, on disk, for every process after`() {
        times.recordFirstRun()
        now = 6_000
        times.recordFirstRun()

        assertEquals(5_000L, times.firstRunMs)
        val nextProcess = OldEntryTime(prefs) { now }
        nextProcess.recordFirstRun()
        assertEquals(5_000L, nextProcess.firstRunMs)
    }

    /** [real], with a disk that refuses the next [failures] commits, writing nothing for them; [committedOn] names each commit's thread. */
    private class FullDisk(private val real: SharedPreferences, var failures: Int) : SharedPreferences by real {
        val committedOn = mutableListOf<String>()

        override fun edit(): SharedPreferences.Editor {
            val edit = real.edit()
            return object : SharedPreferences.Editor by edit {
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { edit.putLong(key, value) }

                override fun commit(): Boolean {
                    committedOn += threadName()
                    if (failures == 0) return edit.commit()
                    failures--
                    return false
                }
            }
        }
    }

    @Test
    fun `a first run the disk did not take is written again at the next call, until it lands`() {
        val disk = FullDisk(prefs, failures = 2)
        val times = OldEntryTime(disk) { now }
        times.recordFirstRun()
        assertEquals("this process keeps it", 5_000L, times.firstRunMs)
        assertNull("not on disk yet", OldEntryTime(prefs) { now }.firstRunMs)

        now = 7_000
        assertEquals(5_000L, runBlocking { times.oldEntryTimeMs(null) })
        assertNull("the disk refused it again", OldEntryTime(prefs) { now }.firstRunMs)
        times.recordFirstRun()

        // A later start reads the time this build first ran, not its own.
        now = 9_000
        val nextProcess = OldEntryTime(prefs) { now }
        nextProcess.recordFirstRun()
        assertEquals(5_000L, nextProcess.firstRunMs)
        assertEquals(5_000L, runBlocking { nextProcess.oldEntryTimeMs(null) })
    }

    @Test
    fun `the owed write and the file's time are done off the caller's thread`() {
        val io = Executors.newSingleThreadExecutor { Thread(it, "outbox-test-io") }.asCoroutineDispatcher()
        try {
            val disk = FullDisk(prefs, failures = 1)
            val times = OldEntryTime(disk, io) { now }
            // At the process's start, on the caller's thread, before anything writes a queue; the disk refuses it.
            times.recordFirstRun()
            disk.committedOn.clear()
            file.setLastModified(1_000)
            val readOn = mutableListOf<String>()
            val queueFile = object : File(file.path) {
                override fun lastModified(): Long {
                    readOn += threadName()
                    return super.lastModified()
                }
            }

            assertEquals(1_000L, runBlocking { times.oldEntryTimeMs(queueFile) })

            assertEquals("the owed write", listOf("outbox-test-io"), disk.committedOn)
            assertEquals("the file's time", listOf("outbox-test-io"), readOn)
            assertEquals("and it landed", 5_000L, OldEntryTime(prefs) { now }.firstRunMs)
        } finally {
            io.close()
        }
    }

    @Test
    fun `built as the app builds it, it has recorded the first run`() {
        val before = System.currentTimeMillis()
        val built = OldEntryTime(JvmContext(), Dispatchers.Unconfined)

        val recorded = built.firstRunMs
        assertTrue("$recorded", recorded != null && recorded in before..System.currentTimeMillis())
    }

    @Test
    fun `the app builds it as the process starts`() {
        val path = "src/main/java/org/ntust/app/tigerduck/TigerDuckApp.kt"
        val app = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, path), File(it, "app/$path")) }
            .first { it.isFile }
            .readText()

        assertTrue(Regex("""@Inject\s+lateinit var oldEntryTime: [\w.]*OldEntryTime\b""").containsMatchIn(app))
    }

    @Test
    fun `an old-entry time is never later than now`() {
        file.setLastModified(8_000)
        times.recordFirstRun()
        now = 3_000

        assertEquals(3_000L, runBlocking { times.oldEntryTimeMs(file) })
    }
}

/** The thread this runs on, without the coroutine a debug build names after it. */
private fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
