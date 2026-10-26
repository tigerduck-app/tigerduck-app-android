@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Marks tapped here whose PATCH failed ([UnsentMarks]), over real files. A
 * failed PATCH used to be only logged: once the account's marks had been
 * through a sync, the next one took the server — which never got the mark —
 * as the truth and wiped it here.
 */
class UnsentMarksTest {

    private lateinit var cache: DataCache
    private lateinit var marks: UnsentMarks

    private val sent = mutableListOf<Pair<Int, String>>()
    private var answer: suspend (Int, String) -> Unit = { _, _ -> }
    /** Mark sync on: the Assignments switch and TigerSync. */
    private var records = true

    /** And no assignments re-enable check holding the marks. */
    private var sends = true

    @Before
    fun setUp() {
        val context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        marks = newProcess()
    }

    /** What a new process builds over the same files. */
    private fun newProcess() = UnsentMarks(
        dataCache = cache,
        sender = MarkSender { id, status, signIn ->
            signIns += signIn
            sent += id to status
            answer(id, status)
        },
        records = { records },
        sends = { records && sends },
        signInGeneration = { signInGeneration },
        clock = { now },
    )

    private var now = 1_000_000L

    /** The sign-in each PATCH was pinned to. */
    private val signIns = mutableListOf<Int>()
    private var signInGeneration = 1

    private suspend fun waiting() = cache.loadUnsentMarks().associate { it.assignmentId to it.status }

    @Test
    fun `a tap whose PATCH fails is kept, and the next sync sends it and lets it go`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "locally_completed")
        assertEquals(setOf("7"), marks.ids())

        answer = { _, _ -> }
        marks.retry()

        assertEquals(listOf(7 to "locally_completed", 7 to "locally_completed"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `a tap that lands is not kept`() = runTest {
        marks.send("7", "ignored")

        assertEquals(listOf(7 to "ignored"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `an older tap landing late does not let a newer one go`() = runTest {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { _, status ->
            if (status == "ignored") {
                first.complete(Unit)
                release.await()
            } else {
                throw IOException("offline")
            }
        }

        val older = launch { marks.send("7", "ignored") }
        first.await()
        val newer = launch { marks.send("7", "none") }
        testScheduler.runCurrent()
        release.complete(Unit)
        older.join()
        newer.join()

        assertEquals(mapOf("7" to "none"), waiting())
    }

    @Test
    fun `nothing is sent or kept with mark sync off, and what waits stays until it is on`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")
        records = false

        marks.send("8", "ignored")
        marks.retry()

        assertEquals(listOf(7 to "ignored"), sent)
        assertEquals(setOf("7"), marks.ids())
    }

    @Test
    fun `what waits survives a new process`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")

        assertEquals(setOf("7"), newProcess().ids())
    }

    @Test
    fun `taking the server's marks drops what waits`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")

        marks.forget()

        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `signing out drops what waits`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")

        cache.clearAllUserData()

        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `a mark Use Local could not send is kept while the re-enable check still holds marks`() = runTest {
        // While the answer is applied, "assignments" is still pending and no
        // mark goes out; what Use Local failed to send must wait regardless.
        sends = false

        marks.keep("7", "locally_completed", cache.userDataGeneration)

        assertEquals(mapOf("7" to "locally_completed"), waiting())
        assertEquals(emptyList<Pair<Int, String>>(), sent)
    }

    @Test
    fun `a mark kept for an account that has signed out since is not written`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()

        marks.keep("7", "ignored", generation)

        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `a waiting mark a newer tap replaced is not sent over it`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("8", "ignored")
        marks.send("7", "ignored")
        val eightOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { id, _ ->
            if (id == 8) {
                eightOut.complete(Unit)
                release.await()
            }
        }
        sent.clear()

        // A sync's retry reads both, and sends 8 first.
        val retry = launch { marks.retry() }
        eightOut.await()
        // The user unmarks 7 while 8 is out.
        val tap = launch { marks.send("7", "none") }
        testScheduler.runCurrent()
        release.complete(Unit)
        retry.join()
        tap.join()

        assertEquals(listOf(8 to "ignored", 7 to "none"), sent)
    }

    @Test
    fun `a waiting mark is not sent once the account has signed out`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")
        marks.send("8", "ignored")
        sent.clear()
        answer = { id, _ ->
            if (id == 7) {
                // Signed out, and someone else signed in, while 7 was out.
                cache.clearAllUserData()
                signInGeneration++
            }
        }

        marks.retry()

        assertEquals(listOf(7 to "ignored"), sent)
    }

    @Test
    fun `every PATCH is pinned to the sign-in that was current when its mark was read`() = runTest {
        signInGeneration = 4

        marks.send("7", "ignored")

        assertEquals(listOf(4), signIns)
    }

    @Test
    fun `two overlapping syncs send a waiting mark once`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")
        sent.clear()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { _, _ ->
            out.complete(Unit)
            release.await()
        }

        val one = launch { marks.retry() }
        out.await()
        val two = launch { marks.retry() }
        testScheduler.runCurrent()
        release.complete(Unit)
        one.join()
        two.join()

        assertEquals(listOf(7 to "ignored"), sent)
    }

    @Test
    fun `a tap made while marks do not sync replaces what waited for the same assignment`() = runTest {
        // Marked done offline, then the Assignments switch went off, then the
        // mark was cleared: the clear is the newer choice.
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "locally_completed")
        records = false
        marks.send("7", "none")

        records = true
        answer = { _, _ -> }
        sent.clear()
        marks.retry()

        assertEquals(emptyList<Pair<Int, String>>(), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `a tap made while the re-enable check holds the marks waits, and goes up once it ends`() = runTest {
        sends = false

        marks.send("7", "ignored")
        assertEquals(emptyList<Pair<Int, String>>(), sent)
        assertEquals(setOf("7"), marks.ids())

        sends = true
        marks.retry()

        assertEquals(listOf(7 to "ignored"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `a mark the backend keeps refusing is parked and left out of later syncs`() = runTest {
        // A 404: the backend has no such assignment for this account.
        answer = { _, _ -> throw PushApiException("HTTP 404", 404) }
        marks.send("7", "ignored")
        repeat(UnsentMarks.MAX_ATTEMPTS + 2) { marks.retry() }

        assertEquals(UnsentMarks.MAX_ATTEMPTS, sent.size)
        assertEquals("still kept at this phone's value", setOf("7"), marks.ids())
    }

    @Test
    fun `a parked mark gets one more try in a new process`() = runTest {
        answer = { _, _ -> throw PushApiException("HTTP 404", 404) }
        marks.send("7", "ignored")
        repeat(UnsentMarks.MAX_ATTEMPTS) { marks.retry() }
        sent.clear()

        val next = newProcess()
        next.retry()
        next.retry()

        assertEquals(listOf(7 to "ignored"), sent)
    }

    @Test
    fun `a server error or no network is not counted as a refusal`() = runTest {
        answer = { _, _ -> throw PushApiException("HTTP 503", 503) }
        marks.send("7", "ignored")
        repeat(UnsentMarks.MAX_ATTEMPTS) { marks.retry() }
        answer = { _, _ -> throw IOException("offline") }
        repeat(UnsentMarks.MAX_ATTEMPTS) { marks.retry() }

        assertEquals(1 + 2 * UnsentMarks.MAX_ATTEMPTS, sent.size)
    }

    @Test
    fun `a new tap of a parked mark goes up at once`() = runTest {
        answer = { _, _ -> throw PushApiException("HTTP 404", 404) }
        marks.send("7", "ignored")
        repeat(UnsentMarks.MAX_ATTEMPTS) { marks.retry() }
        answer = { _, _ -> }
        sent.clear()

        marks.send("7", "none")

        assertEquals(listOf(7 to "none"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `Use Local's older reading does not replace a tap made during the hold`() = runTest {
        sends = false
        // Tapped on Home while Use Local's requests were going out.
        marks.send("7", "none")

        // Use Local read the mark before that tap, and its PATCH failed.
        marks.keep("7", "ignored", cache.userDataGeneration)

        assertEquals(mapOf("7" to "none"), waiting())
    }

    @Test
    fun `a parked mark is tried again once eight hours have passed`() = runTest {
        // A 404 until the backend's own Moodle fetch creates the assignment's
        // row, which runs every eight hours; the app may stay open for days.
        answer = { _, _ -> throw PushApiException("HTTP 404", 404) }
        marks.send("7", "ignored")
        repeat(UnsentMarks.MAX_ATTEMPTS) { marks.retry() }
        sent.clear()

        now += 60 * 60 * 1000L
        marks.retry()
        assertEquals("parked for now", emptyList<Pair<Int, String>>(), sent)

        now += 7 * 60 * 60 * 1000L
        answer = { _, _ -> }
        marks.retry()

        assertEquals(listOf(7 to "ignored"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `the status each waiting mark set is at hand for a sync to apply`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "locally_completed")
        marks.send("8", "none")

        assertEquals(mapOf("7" to "locally_completed", "8" to "none"), marks.statuses())
    }
}
