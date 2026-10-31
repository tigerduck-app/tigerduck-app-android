@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A course deleted here whose backend DELETE failed ([PendingCourseDeletes]), over real files.
 * It used to go once: a course taken back out of Add course writes no tombstone here, so a
 * failed DELETE left its row on the server for the next sync to merge back as a hand-added
 * course (and a regular delete's tombstone did not help: the reconcile un-hides a course the
 * server still lists). Now the delete waits on disk, every full sync sends it again first,
 * and the reconcile keeps the course out until it lands.
 *
 * Sent under the lock course adds and term resets take ([PendingCourseAdds.removing]), pinned
 * to the sign-in that queued it, and only while still queued: a retry read before a re-add or
 * a reset must not take the restored course off the server, nor send as the next account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingCourseDeletesTest {

    private lateinit var cache: DataCache
    private lateinit var adds: PendingCourseAdds
    private lateinit var deletes: PendingCourseDeletes

    private data class Sent(val courseKey: String, val account: Int)

    private val sent = mutableListOf<Sent>()

    // The moment each DELETE went with.
    private val hiddenAts = mutableListOf<Long>()
    private var answer: suspend (String) -> Unit = {}
    private val uploads = mutableListOf<List<String>>()
    private val events = mutableListOf<String>()

    // AuthTokenManager.signInGeneration, and what happens before a request's header is made.
    private var signIn = 1
    private var beforeHeader: suspend () -> Unit = {}

    private val term = "1151"
    private val typed = "MA1010002"
    private val other = "PE1010003"
    private fun course(no: String) = Course(courseNo = no, courseName = no, isManual = true)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        adds = PendingCourseAdds(cache, { courses, _, _, pinned, _ ->
            if (pinned != signIn) throw NotSignedInException()
            uploads += courses.map { it.courseNo }
            events += "upload"
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        }, signInGeneration = { signIn })
        // As PushApiClient.deleteCourse: pinned to a sign-in, the header is that account's or none.
        deletes = PendingCourseDeletes(cache, adds, { courseKey, pinned, hiddenAt ->
            beforeHeader()
            if (pinned != signIn) throw NotSignedInException()
            sent += Sent(courseKey, account = signIn)
            hiddenAts += hiddenAt
            answer(courseKey)
            events += "DELETE"
        }, signInGeneration = { signIn }, clock = { ++tick })
    }

    // A clock that stands still between ticks, as a real one does within a millisecond.
    private var tick = 0L

    private fun keys() = sent.map { it.courseKey }

    /** The queued course numbers by term, without the moments they were asked at. */
    private suspend fun queued() = cache.loadPendingCourseDeletes().mapValues { (_, entries) -> entries.keys.sorted() }

    /**
     * Runs the test dispatcher until [done]. A launched coroutine's file reads and writes run on
     * the IO dispatcher and resume into the test dispatcher on their own time, which
     * `runCurrent()` alone does not wait for; each [done] is itself a real read.
     */
    private suspend fun TestScope.settle(done: suspend () -> Boolean) {
        repeat(500) {
            if (done()) return
            runCurrent()
            yield()
        }
        throw AssertionError("the outbox did not settle")
    }

    /**
     * The moments the class table gives adds come from [PendingCourseDeletes.stamp] too: after a
     * restart with the clock set back, a hide asked now still comes after an add kept from before.
     */
    @Test
    fun `a stamp after a restart comes after every add's moment kept`() = runTest {
        cache.savePendingCourseAddTimes(mapOf(term to mapOf(typed to 9_000L)))
        val restarted = PendingCourseDeletes(cache, adds, { _, _, _ -> }, signInGeneration = { signIn }, clock = { 1L })

        assertTrue(restarted.stamp() > 9_000L)
    }

    /**
     * A delete goes with the moment it was asked (#591), again on a retry: the backend applies
     * it only when later than the course's last add, hide and reset, so a hide kept offline
     * loses to an add made since on another device, and a retry after a lost reply changes
     * nothing.
     */
    @Test
    fun `a delete goes with the moment it was asked, again on a retry`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed, queuedAt = 7_000)

        answer = {}
        deletes.retry()

        assertEquals(listOf(7_000L, 7_000L), hiddenAts)
    }

    @Test
    fun `a delete that fails is kept, and the next sync sends it again with the same key`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        assertEquals(listOf("client:1151:MA1010002"), keys())
        assertEquals(mapOf(term to listOf(typed)), queued())
        // What the reconcile keeps out of the term meanwhile.
        assertEquals(setOf(typed), deletes.waitingIn(term))
        assertEquals(emptySet<String>(), deletes.waitingIn("1142"))

        answer = { throw PushApiException("deleteCourse failed: HTTP 500") }
        deletes.retry()
        assertEquals(mapOf(term to listOf(typed)), queued())

        answer = {}
        deletes.retry()
        assertEquals(listOf("client:1151:MA1010002", "client:1151:MA1010002", "client:1151:MA1010002"), keys())
        assertEquals(emptyMap<String, List<String>>(), queued())
        assertEquals(emptySet<String>(), deletes.waitingIn(term))
    }

    /** The class table keeps a delete before its hide is written, and sends it after. */
    @Test
    fun `a delete kept ahead of its send waits on disk, unsent, until the send takes it`() = runTest {
        val queuedAt = deletes.stamp()
        deletes.keep(term, typed, queuedAt)
        assertEquals(mapOf(term to listOf(typed)), queued())
        assertEquals(emptyList<String>(), keys())

        deletes.send(term, typed, queuedAt)
        assertEquals(listOf("client:1151:MA1010002"), keys())
        assertEquals(emptyMap<String, List<String>>(), queued())
    }

    /**
     * The class table keeps its delete, then sends it once its writes are done. A full sync's retry
     * can send it in between, and a course selection answer bring the course back with the roster:
     * sent again then, the DELETE would take the course off the server after its upload.
     */
    @Test
    fun `a kept delete a retry has already sent is not sent again`() = runTest {
        deletes.keep(term, typed, deletes.stamp())
        deletes.retry()
        assertEquals(emptyMap<String, List<String>>(), queued())
        // The roster brings the course back meanwhile.
        cache.saveCourses(listOf(course(typed)), term)

        deletes.sendKept(term, typed)

        assertEquals(listOf("client:1151:MA1010002"), keys())
        assertEquals(emptyMap<String, List<String>>(), queued())
    }

    @Test
    fun `a delete that lands is not kept`() = runTest {
        deletes.send(term, typed)
        assertEquals(listOf("client:1151:MA1010002"), keys())
        assertEquals(emptyMap<String, List<String>>(), queued())
    }

    /** Adding the course back, or resetting its term under the adds lock, drops the delete still queued for it. */
    @Test
    fun `a course added back or a term reset lets its queued delete go`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        deletes.send(term, other)
        deletes.send("1142", typed)

        deletes.forget(term, typed)
        assertEquals(mapOf(term to listOf(other), "1142" to listOf(typed)), queued())

        adds.removing { deletes.forgetTerm(term) }
        assertEquals(mapOf("1142" to listOf(typed)), queued())

        deletes.retry()
        assertEquals(listOf("client:1142:MA1010002"), keys().drop(3))
    }

    /**
     * A course reset made on another device, read from a full sync's snapshot, brings back the
     * courses this phone's queued deletes took (`HomeBackendSync.applyCourseReset`). Those asked
     * before the fetch go, in every term, under the adds lock; one asked after it is newer than
     * the reset the snapshot read, and stays.
     */
    @Test
    fun `a course reset read from another device lets go of the deletes asked before its fetch`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        deletes.send("1142", typed)
        val fetch = deletes.stamp()
        deletes.send(term, other)

        adds.removing { deletes.forgetAskedBefore(fetch) }
        assertEquals(mapOf(term to listOf(other)), queued())

        // Sent again, either would have taken a restored course off the server.
        answer = {}
        deletes.retry()
        assertEquals(listOf("client:1151:PE1010003"), keys().drop(3))
    }

    /**
     * The class table stamps a removal as the student acts and keeps it only after its own writes
     * (`ClassTableViewModel.removeAddedCourse`). A reset's let-go landing in between must still
     * take it: kept after, it would go up and take the restored course off the server.
     */
    @Test
    fun `a delete asked before a reset's fetch but kept after its let-go is not kept`() = runTest {
        val asked = deletes.stamp()
        val alsoAsked = deletes.stamp()
        val fetch = deletes.stamp()
        adds.removing { deletes.forgetAskedBefore(fetch) }

        deletes.keep(term, typed, asked)
        deletes.send(term, other, alsoAsked)
        assertEquals(emptyMap<String, List<String>>(), queued())
        assertEquals(emptyList<String>(), keys())

        // One asked after the fetch is newer than the reset, and goes.
        deletes.send(term, other)
        assertEquals(listOf("client:1151:PE1010003"), keys())
    }

    /** Account-scoped: a sign-out's wipe takes the queue with the rest of the user's data. */
    @Test
    fun `a sign-out leaves no delete to send as the next account`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)

        cache.clearAllUserData()

        assertEquals(emptyMap<String, List<String>>(), queued())
        deletes.retry()
        assertEquals(1, sent.size)
    }

    @Test
    fun `a delete read as one account is not sent as the next one`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)

        // The account signs out, and another signs in, before the retry's request has its header.
        answer = {}
        beforeHeader = {
            beforeHeader = {}
            cache.clearAllUserData()
            signIn++
        }
        deletes.retry()

        assertEquals(emptyList<Sent>(), sent.filter { it.account == 2 })
        assertEquals(emptyMap<String, List<String>>(), queued())
    }

    /** The retry read both deletes; the second course was added back while the first was sent. */
    @Test
    fun `a retried delete whose course was added back meanwhile is not sent`() = runTest {
        cache.saveCourses(listOf(course(typed), course(other)), term)
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        deletes.send(term, other)

        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
        }
        val retry = launch { deletes.retry() }
        sending.await()
        // Added back by hand meanwhile, as ClassTableViewModel.addCourse does.
        val readd = launch {
            deletes.forget(term, other)
            adds.send(listOf(course(typed), course(other)), term, other)
        }
        runCurrent()
        answer = {}
        landed.complete(Unit)
        retry.join()
        readd.join()

        // Its stale DELETE would have taken the restored course off the server again.
        assertEquals(1, keys().count { it == "client:1151:PE1010003" })
        // The add went up (the term's list, as an add sends it), after the first course's DELETE.
        assertEquals(listOf(listOf(typed, other)), uploads)
        assertEquals(listOf("DELETE", "upload"), events)
        assertEquals(emptyMap<String, List<String>>(), queued())
    }

    /**
     * The first DELETE is slow; the user adds the course back (its forget waits for the lock)
     * and removes it again (a second delete). The first DELETE then fails: the re-add's forget
     * may let go only of the delete asked before it, not of the second one, which must stay
     * queued and be sent, or the server keeps the course with no delete queued.
     */
    @Test
    fun `a delete asked after a re-add survives the re-add's forget`() = runTest {
        cache.saveCourses(listOf(course(typed)), term)
        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
            throw IOException("timed out")
        }
        val first = launch { deletes.send(term, typed) }
        sending.await()
        val readd = launch { deletes.forget(term, typed) }
        runCurrent()
        val again = launch { deletes.send(term, typed) }
        runCurrent()
        landed.complete(Unit)
        first.join()
        readd.join()
        again.join()

        assertEquals("the second delete stays queued", setOf(typed), deletes.waitingIn(term))
        answer = {}
        deletes.retry()
        assertEquals(emptySet<String>(), deletes.waitingIn(term))
        assertEquals("client:1151:MA1010002", keys().last())
    }

    /**
     * The view model stamps a re-add and a removal as the user asks for them; the work that
     * follows each may reach the lock in either order. A forget lets go only of a delete asked
     * before its re-add; stamps are later than every earlier one, whatever the clock does.
     */
    @Test
    fun `a re-add lets go only of a delete asked before it`() = runTest {
        answer = { throw IOException("offline") }
        val before = deletes.stamp()
        tick = 0 // The clock stands still, or goes back.
        val removedAt = deletes.stamp()
        assertTrue("later than the one before it", removedAt > before)
        deletes.send(term, typed, queuedAt = removedAt)

        deletes.forget(term, typed, askedAt = before)
        assertEquals("a re-add asked before the removal keeps it", setOf(typed), deletes.waitingIn(term))

        deletes.forget(term, typed, askedAt = deletes.stamp())
        assertEquals("one asked after it lets it go", emptySet<String>(), deletes.waitingIn(term))
    }

    /**
     * A failed delete waited across a restart, and the clock moved back meanwhile. The next
     * process's stamps must still outrank it, or a re-add keeps the old delete and a retry
     * takes the restored course off.
     */
    @Test
    fun `after a restart a new action outranks every queued delete, whatever the clock says`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed, queuedAt = 1_000)
        assertEquals(mapOf(term to listOf(typed)), queued())

        val restarted = PendingCourseDeletes(cache, adds, { courseKey, pinned, _ ->
            if (pinned != signIn) throw NotSignedInException()
            sent += Sent(courseKey, account = signIn)
            answer(courseKey)
        }, signInGeneration = { signIn }, clock = { 5 })

        assertTrue("a stamp after the restart outranks the queued delete", restarted.stamp() > 1_000)
        restarted.forget(term, typed)
        assertEquals("the re-add lets the old delete go", emptySet<String>(), restarted.waitingIn(term))
        answer = {}
        restarted.retry()
        assertEquals(1, sent.size)
    }

    /**
     * A process's first stamp reads the ones kept, and a second asked meanwhile waits for it.
     * On the main thread's immediate dispatcher the lock going resumed the second one inline,
     * which took its stamp before the first: a re-add asked after a removal then ranked below
     * it, and its forget kept the delete that takes the restored course off.
     */
    @Test
    fun `two first stamps of a process rank in the order they were asked`() {
        runBlocking { cache.savePendingCourseDeletes(mapOf(term to mapOf(typed to 1_000L))) }
        // The read goes back through the main looper first, so the first stamp always holds the
        // lock over it when the second is asked, and resumes from the looper, not inline.
        val fresh = PendingCourseDeletes(
            cache, adds, { _, _, _ -> },
            signInGeneration = { signIn },
            clock = { ++tick },
            load = { yield(); cache.loadPendingCourseDeletes() },
        )
        val stamps = mutableMapOf<String, Long>()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val first = scope.launch { stamps["first"] = fresh.stamp() }
        val second = scope.launch { stamps["second"] = fresh.stamp() }
        assertTrue("the first holds the lock over its read, the second waits for it", !first.isCompleted && !second.isCompleted)
        val deadline = System.currentTimeMillis() + 5_000
        while (!(first.isCompleted && second.isCompleted) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue("both stamps taken", first.isCompleted && second.isCompleted)
        assertTrue("the first asked has the earlier stamp: $stamps", stamps.getValue("first") < stamps.getValue("second"))
        assertTrue("both above the delete kept", stamps.values.all { it > 1_000 })
    }

    /**
     * A slow request holds the lock; a removal asked meanwhile must already be on disk while it
     * waits, or a process killed then loses it, and the next sync restores the server row.
     */
    @Test
    fun `a delete waiting for the lock is already kept on disk`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val holder = launch { adds.removing { gate.await() } }
        runCurrent()

        answer = { throw IOException("offline") }
        val removal = launch { deletes.send(term, typed) }
        // The write goes through real file reads and writes, which resume into
        // the test dispatcher on their own time: drive it until the file has it.
        settle { queued().isNotEmpty() }
        assertEquals("kept before the wait", mapOf(term to listOf(typed)), queued())
        assertEquals("not sent while the lock is held", emptyList<String>(), keys())
        assertTrue("still waiting for the lock", removal.isActive)

        // The process dies here: a new one finds the delete and sends it at its next sync.
        removal.cancel()
        gate.complete(Unit)
        holder.join()
        answer = {}
        val restarted = PendingCourseDeletes(cache, adds, { courseKey, pinned, _ ->
            if (pinned != signIn) throw NotSignedInException()
            sent += Sent(courseKey, account = signIn)
            answer(courseKey)
        }, signInGeneration = { signIn })
        restarted.retry()
        assertEquals(listOf("client:1151:MA1010002"), keys())
        assertEquals(emptyMap<String, List<String>>(), queued())
    }

    /** A delete under way holds the adds back, as a delete by hand did before it was queued. */
    @Test
    fun `a course add waits for a delete being sent`() = runTest {
        cache.saveCourses(listOf(course(typed)), term)
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        events.clear()

        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
        }
        val retry = launch { deletes.retry() }
        sending.await()
        val add = launch { adds.send(listOf(course(typed)), term, typed) }
        runCurrent()
        assertEquals("the add waits for the DELETE", emptyList<List<String>>(), uploads)
        landed.complete(Unit)
        retry.join()
        add.join()

        // The other way round, the add's force key would lose to the DELETE landing after it.
        assertEquals(listOf("DELETE", "upload"), events)
    }
}
