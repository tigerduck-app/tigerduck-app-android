package org.ntust.app.tigerduck.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The course deletes as an outbox channel ([PendingCourseDeletes]), over real files and the real
 * backend client, against a backend that records what reaches it.
 *
 * A delete goes with the moment it was asked and no kind, one made with course sync off is kept,
 * and one the backend refused, the course being added since elsewhere, lifts this phone's hide
 * unless the course was deleted again.
 */
class CourseDeletesChannelTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val typed = "MA1010002"
    private val other = "PE1010003"
    private val bearer = "Authorization" to "Bearer access"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private val v4 = "http://127.0.0.1:9/v4"

    /** The wall clock, which a test moves. */
    private var nowMs = 1_790_000_000_000L
    private val syncClock = SyncClock(SyncClockStore(context.getSharedPreferences("sync-clock", Context.MODE_PRIVATE)), { nowMs })
    private val ack = AckClock()

    /** The deletes and the adds as Hilt builds them, on `/v4`. */
    private fun TestScope.v4Deletes(): Pair<PendingCourseDeletes, PendingCourseAdds> {
        val client = pushApiClientAt(v4, backend.client, prefs, syncClock)
        val adds = PendingCourseAdds(cache, client, SyncPolicies(prefs), ack, backgroundScope)
        return PendingCourseDeletes(cache, adds, client, syncClock, SyncPolicies(prefs), ack, backgroundScope) to adds
    }

    private fun field(courseNo: String, semester: String = term) = SyncField.membership(SyncField.courseKey(semester, courseNo))

    private fun deletePath(courseNo: String, semester: String = term) = "/v4/sync/courses/client:$semester:$courseNo"

    private fun deleteAnswer(applied: Boolean) = """{"deleted": ${if (applied) 1 else 0}, "applied": $applied, "hlc": 1790000000123000}"""

    private suspend fun queued() = cache.loadPendingCourseDeletes()

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
        throw AssertionError("the queue did not settle")
    }

    /** A delete is never given up on for its refusals: every sync sends it again, as before it was a channel. */
    @Test
    fun `on v3 a delete refused time after time still goes at every sync`() = runTest {
        var refusals = 0
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(cache, adds, { _, _, _ ->
            if (refusals++ < 6) throw PushApiException("deleteCourse failed: HTTP 422", 422)
        })
        deletes.send(term, typed, 5_000L, signIn)

        repeat(6) { deletes.retry(signIn) }

        assertEquals(7, refusals)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * The course removed again while its first DELETE is out: the second goes at its own turn of
     * the send lock, after an upload that was waiting for the lock before it, as it always went.
     */
    @Test
    fun `on v3 a removal made while its first DELETE is out goes at its own turn of the send lock`() = runTest {
        val events = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate: suspend () -> Unit = {}
        gate = {
            gate = {}
            out.complete(Unit)
            release.await()
        }
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(cache, adds, { _, _, hiddenAt ->
            gate()
            events += "DELETE $hiddenAt"
        })

        val first = launch { deletes.send(term, typed, 10L, signIn) }
        out.await()
        val upload = launch { adds.uploading { events += "upload" } }
        runCurrent()
        val again = launch { deletes.send(term, typed, 20L, signIn) }
        settle { queued()[term]?.get(typed) == 20L }
        release.complete(Unit)
        first.join()
        upload.join()
        again.join()

        assertEquals(listOf("DELETE 10", "upload", "DELETE 20"), events)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * A caller that leaves while its DELETE is out does not cut it short, its answer is kept, and
     * the send lock stays held until then, so an upload waiting for it does not start beside the
     * DELETE (spec §5.3).
     */
    @Test
    fun `a caller cancelled during its DELETE leaves it to finish under the lock, and its answer is stored`() = runTest {
        val events = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(cache, adds, { _, _, _ ->
            out.complete(Unit)
            release.await()
            events += "DELETE"
        })

        val caller = launch { deletes.send(term, typed, 10L, signIn) }
        out.await()
        val upload = launch { adds.uploading { events += "upload" } }
        runCurrent()
        caller.cancelAndJoin()
        runCurrent()
        assertEquals("the upload waits for the DELETE its caller left", emptyList<String>(), events)
        release.complete(Unit)
        upload.join()
        settle { queued().isEmpty() }

        assertEquals(listOf("DELETE", "upload"), events)
    }

    /**
     * Codex on 671844752: a course upload's requeue reaches parked deletes only, as on dev, and a
     * delete never parks: one refused while an upload lands is kept and goes at the next retry.
     */
    @Test
    fun `a delete refused while a course upload lands is kept and goes again at the next retry`() = runTest {
        var calls = 0
        val out = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null })
        val deletes = PendingCourseDeletes(cache, adds, { _, _, _ ->
            if (++calls == 1) {
                out.complete(Unit)
                reply.await()
                throw PushApiException("course DELETE failed: HTTP 404", 404)
            }
        })

        val caller = launch { deletes.send(term, typed, 10L, signIn) }
        out.await()
        Outbox(setOf(adds, deletes)).courseUploadLanded(signIn)
        reply.complete(Unit)
        caller.join()
        assertEquals(mapOf(term to mapOf(typed to 10L)), queued())

        deletes.retry(signIn)

        assertEquals(2, calls)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /** Review Focus 1: a delete an older build kept goes out once on `/v4`, with the moment it was asked. */
    @Test
    fun `an entry an older build wrote goes out on v4 stamped`() = runTest {
        val folder = File(context.filesDir, "TigerDuckData").apply { mkdirs() }
        val fixture = checkNotNull(javaClass.getResource("/account/queues/pending_course_deletes.json")).readText().trimEnd()
        File(folder, "pending_course_deletes.json").writeText(fixture)
        val (deletes, _) = v4Deletes()
        backend.answer(deletePath("CS1003", "1141"), 200, syncFixture("v4/answer-delete.json"))

        deletes.retry(signIn)

        assertEquals(
            listOf(listOf("DELETE", "$v4/sync/courses/client:1141:CS1003?hlc=1700000001000000", listOf(bearer), null, "")),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /** LWW D12: on `/v4` a delete is kept while course sync is off, and goes once it is on. */
    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        prefs.syncCourses = false
        val (deletes, _) = v4Deletes()

        deletes.send(term, typed, 1_790_000_000_123L, signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(mapOf(term to mapOf(typed to 1_790_000_000_123L)), queued())
        assertEquals(setOf(field(typed)), deletes.heldFields())

        prefs.syncCourses = true
        deletes.retry(signIn)

        assertEquals(listOf("$v4/sync/courses/client:$term:$typed?hlc=1790000000123000"), backend.sent.map { it.url })
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * A delete the backend refused, an add of the course made since elsewhere being newer: this
     * phone's hide of it goes, so the next pull shows the course the backend lists (LWW D12, a
     * write response's winner). One it applied leaves the hide where it is.
     */
    @Test
    fun `a refused v4 write applies the backend's value unless a newer edit waits`() = runTest {
        val (deletes, _) = v4Deletes()
        cache.saveDeletedCourseNos(CourseTombstoneKeys.hide(other, term, CourseTombstoneKeys.hide(typed, term, emptySet())), signIn)
        backend.answer(deletePath(typed), 200, deleteAnswer(applied = false))
        backend.answer(deletePath(other), 200, deleteAnswer(applied = true))

        deletes.send(term, typed, 1_790_000_000_001L, signIn)
        deletes.send(term, other, 1_790_000_000_002L, signIn)

        assertFalse(CourseTombstoneKeys.isHidden(typed, term, cache.loadDeletedCourseNos()))
        assertTrue(CourseTombstoneKeys.isHidden(other, term, cache.loadDeletedCourseNos()))
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * The course deleted again while the first DELETE is out: the refusal answered the older
     * delete, and the newer one still stands, so the hide stays and the newer delete goes next.
     */
    @Test
    fun `a refused delete leaves the hide while a newer delete of the course waits`() = runTest {
        prefs.announcementApiBaseUrlOverride = v4
        cache.saveDeletedCourseNos(CourseTombstoneKeys.hide(typed, term, emptySet()), signIn)
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate: suspend () -> Unit = {}
        gate = {
            gate = {}
            out.complete(Unit)
            release.await()
        }
        val sent = mutableListOf<Long>()
        val outbox = MembershipOutbox(SyncPolicies(prefs), scope = backgroundScope)
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null }, outbox)
        val deletes = PendingCourseDeletes(
            cache, adds,
            { _, _, hiddenAt ->
                sent += hiddenAt
                gate()
                WriteAnswer(sent = true, serverClockUs = null, applied = if (sent.size == 1) emptySet() else setOf(WriteAnswer.MEMBERSHIP), values = emptyMap())
            },
            { ++nowMs }, cache::loadPendingCourseDeletes, outbox,
        )

        val first = launch { deletes.send(term, typed, 10L, signIn) }
        out.await()
        val again = launch { deletes.send(term, typed, 20L, signIn) }
        settle { queued()[term]?.get(typed) == 20L }
        release.complete(Unit)
        first.join()
        again.join()

        assertEquals(listOf(10L, 20L), sent)
        assertTrue(CourseTombstoneKeys.isHidden(typed, term, cache.loadDeletedCourseNos()))
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }

    /**
     * The class table removes the course again while the backend's refusal of its older delete is
     * applied. The check for a newer delete and the hide's lift are one step under the queue's
     * lock: the newer delete is kept before the check, which then leaves the hide alone, or after
     * the lift, and marks its hide again. Never between the two, where its hide would be lifted
     * while its own DELETE waits, for a course selection answer to bring the course back.
     */
    @Test
    fun `a newer delete kept while an older delete's refusal is applied keeps its hide`() = runTest {
        prefs.announcementApiBaseUrlOverride = v4
        cache.saveDeletedCourseNos(CourseTombstoneKeys.hide(typed, term, emptySet()), signIn)
        val outbox = MembershipOutbox(SyncPolicies(prefs), scope = backgroundScope)
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> null }, outbox)
        lateinit var deletes: PendingCourseDeletes
        var refused = false
        var newer: Job? = null
        // The queue file's read. The first after the older DELETE's refusal is the check for a
        // newer delete: the next removal starts there, and has every chance to land before it.
        val load: suspend () -> Map<String, Map<String, Long>> = {
            val read = cache.loadPendingCourseDeletes()
            if (refused) {
                refused = false
                val removal = launch {
                    // As ClassTableViewModel.deleteCourse: the delete kept, then its hide marked.
                    deletes.keep(term, typed, 20L, signIn)
                    cache.hideDeletedCourse(typed, term, null, signIn)
                }
                newer = removal
                repeat(300) {
                    if (!removal.isCompleted) {
                        cache.loadDeletedCourseNos()
                        yield()
                    }
                }
            }
            read
        }
        deletes = PendingCourseDeletes(
            cache, adds,
            { _, _, hiddenAt ->
                // The older delete is refused, an add of the course elsewhere being newer.
                refused = hiddenAt == 10L
                WriteAnswer(sent = true, serverClockUs = null, applied = if (refused) emptySet() else setOf(WriteAnswer.MEMBERSHIP), values = emptyMap())
            },
            { ++nowMs }, load, outbox,
        )

        deletes.send(term, typed, 10L, signIn)
        checkNotNull(newer) { "the refusal's check read nothing" }.join()

        assertTrue("the newer delete's hide stands", CourseTombstoneKeys.isHidden(typed, term, cache.loadDeletedCourseNos()))
        assertEquals(mapOf(term to mapOf(typed to 20L)), queued())
    }

    /** A delete of the course asked before another, kept after it: the newer moment is the one that stays and goes. */
    @Test
    fun `an older delete kept after a newer one leaves the newer one's moment`() = runTest {
        val (deletes, _) = v4Deletes()
        deletes.keep(term, typed, 1_790_000_000_020L, signIn)
        deletes.keep(term, typed, 1_790_000_000_010L, signIn)

        deletes.retry(signIn)

        assertEquals(listOf("$v4/sync/courses/client:$term:$typed?hlc=1790000000020000"), backend.sent.map { it.url })
    }

    /**
     * Refused as ahead of the backend's clock again after the client's one restamp, a delete says
     * nothing about itself: it goes again at the next sync, a retry, not one of its refusals,
     * which `SendResult.afterFailure` would count a 409 as.
     */
    @Test
    fun `on v4 a delete refused as ahead once more is a retry, not a refusal`() = runTest {
        val client = pushApiClientAt(v4, backend.client, prefs, syncClock)
        repeat(2) { backend.answer(deletePath(typed), 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${nowMs * 1_000}")) }
        val delete = CourseDelete(term, typed, 1_790_000_000_123L)
        val sends = CourseDeleteSends(
            cache,
            { courseKey, pinned, hiddenAt -> client.deleteCourseAnswered(courseKey, pinned, hiddenAt) },
            MembershipOutbox(SyncPolicies(prefs)),
            waiting = { listOf(delete) },
            underQueueLock = { step -> step() },
        )

        var result: SendResult? = null
        sends.one { result = sends.sendOnce(delete, signIn) }

        assertEquals(2, backend.sent.size)
        assertEquals(SendResult.Retry, result)
    }

    /** Review Focus 5, on `/v4`. */
    @Test
    fun `an entry read under an ended sign-in is neither sent nor rewritten`() = runTest {
        val (deletes, _) = v4Deletes()
        prefs.syncCourses = false
        deletes.send(term, typed, 5L, signIn)
        prefs.syncCourses = true
        val waiting = queued()

        val ended = series.end()
        deletes.retry(ended)
        deletes.sendKept(term, typed, ended)
        deletes.keep(term, other, 6L, ended)
        deletes.send(term, other, 7L, ended)
        deletes.queueAsOldest(term, other, ended)
        deletes.forget(term, typed, 8L, ended)
        deletes.forgetTerm(term, ended)
        deletes.forgetAskedBefore(9L, ended)

        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(waiting, queued())
    }

    /**
     * A sent delete's clock is its acknowledgment, which a pull holds the course by (LWW D12).
     * Until it lands the outbox holds the course.
     */
    @Test
    fun `a v4 delete is acknowledged with the backend's clock, and held by the outbox till then`() = runTest {
        val (deletes, adds) = v4Deletes()
        val outbox = Outbox(setOf(adds, deletes))
        backend.answer(deletePath(typed), 500, "{}")

        deletes.send(term, typed, 1_790_000_000_001L, signIn)
        assertTrue(outbox.holds(field(typed)))

        backend.answer(deletePath(typed), 200, deleteAnswer(applied = true), mapOf("X-TigerSync-Clock" to "1790000000500000"))
        outbox.drain(signIn)

        assertEquals(1_790_000_000_500_000L, ack.lastAck(field(typed)))
        assertFalse(outbox.holds(field(typed)))
    }

    /** LWW D13's stamp-0 delete: kept only where none waits, and sent as the oldest. */
    @Test
    fun `a delete queued as the oldest goes stamped 0, and leaves a waiting delete as it is`() = runTest {
        val (deletes, _) = v4Deletes()
        prefs.syncCourses = false
        deletes.send(term, typed, 1_790_000_000_001L, signIn)

        assertTrue(deletes.queueAsOldest(term, typed, signIn))
        assertTrue(deletes.queueAsOldest(term, other, signIn))
        assertEquals(mapOf(term to mapOf(typed to 1_790_000_000_001L, other to 0L)), queued())

        prefs.syncCourses = true
        deletes.retry(signIn)
        assertEquals(
            listOf("$v4/sync/courses/client:$term:$typed?hlc=1790000000001000", "$v4/sync/courses/client:$term:$other?hlc=0"),
            backend.sent.map { it.url },
        )
    }

    /**
     * A delete's moment is the sync clock's ([PendingCourseDeletes.stamp]): never past the
     * backend's time plus five seconds as last heard.
     */
    @Test
    fun `a delete is stamped by the sync clock, capped by the backend on v4`() = runTest {
        // A phone a minute ahead of the backend it has just heard.
        val serverMs = nowMs
        nowMs += 60_000
        syncClock.heard(serverMs * 1_000)
        val (onV4, _) = v4Deletes()
        assertEquals(serverMs + 5_001, onV4.stamp())
        assertEquals(serverMs + 5_002, onV4.stamp())
    }

    /** The outbox sends the adds before the deletes, as every sync's retry did. */
    @Test
    fun `the outbox drains the course adds before the course deletes`() = runTest {
        val typedCourse = Course(courseNo = typed, courseName = "Typed", isManual = true)
        cache.saveCourses(listOf(typedCourse), term, signIn)
        val client = pushApiClientAt(v4, backend.client, prefs)
        val adds = PendingCourseAdds(cache, client)
        val deletes = PendingCourseDeletes(cache, adds, client)
        deletes.keep(term, other, 5L, signIn)
        backend.answer("/v4/sync/courses/upload", 500, "{}")
        adds.send(listOf(typedCourse), term, typed, addedAt = 4L, signIn = signIn)
        backend.sent.clear()

        Outbox(setOf(deletes, adds)).drain(signIn)

        assertEquals(
            listOf("POST $v4/sync/courses/upload", "DELETE $v4/sync/courses/client:$term:$other?hlc=5000"),
            backend.sent.map { "${it.method} ${it.url}" },
        )
    }

    // --- Rules either version keeps, which their /v3 cases pinned, on /v4 ---

    @Test
    fun `on v4 a delete goes with its moment, and a retry goes on past one that fails`() = runTest {
        val (deletes, _) = v4Deletes()
        backend.answer("/v4/sync/courses/client:$term:$typed", 500, "{}")
        deletes.send(term, typed, 1_790_000_000_123L, signIn)
        deletes.keep(term, other, 1_790_000_000_456L, signIn)
        backend.answer("/v4/sync/courses/client:$term:$typed", 500, "{}")

        deletes.retry(signIn)

        assertEquals(
            listOf(
                listOf("DELETE", "$v4/sync/courses/client:$term:$typed?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v4/sync/courses/client:$term:$typed?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v4/sync/courses/client:$term:$other?hlc=1790000000456000", listOf(bearer), null, ""),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(mapOf(term to mapOf(typed to 1_790_000_000_123L)), queued())
    }

    @Test
    fun `on v4 a refused delete is kept and sent again at the next retry`() = runTest {
        val (deletes, _) = v4Deletes()
        backend.answer("/v4/sync/courses/client:$term:$typed", 404, """{"detail":"Course not found"}""")
        deletes.send(term, typed, 1_790_000_000_123L, signIn)
        assertEquals(mapOf(term to mapOf(typed to 1_790_000_000_123L)), queued())

        deletes.retry(signIn)

        assertEquals(2, backend.sent.size)
        assertEquals(emptyMap<String, Map<String, Long>>(), queued())
    }
}
