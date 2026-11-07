package org.ntust.app.tigerduck.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
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
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.push.CourseUploadListener
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.SendResult
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The course adds as an outbox channel ([PendingCourseAdds]), over real files and the real backend
 * client, against a backend that records what reaches it.
 *
 * The adds an older build kept go with their ages (Review Focus 1), an add made with course sync off is
 * kept, a refusal is the backend's to settle unless the course was added again, and a landed
 * upload is acknowledged and tells the outbox.
 */
class CourseAddsChannelTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val typed = Course(courseNo = "MA1010002", courseName = "Typed", isManual = true, customColorHex = "#336699")
    private val untyped = Course(courseNo = "PE1010003", courseName = "Untimed", isManual = true)
    private val later = Course(courseNo = "XB0000002", courseName = "Next term", isManual = true)

    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    private val v4 = "http://127.0.0.1:9/v4"
    private val upload = "/v4/sync/courses/upload"

    /** The wall clock, which a test moves. */
    private var nowMs = FIRST_RUN_MS
    private val deviceClocks = DeviceClocks.wallOnly { nowMs }
    private val ack = AckClock()

    // This build first ran at FIRST_RUN_MS, and it is NOW_MS since.
    private val oldEntryTime = OldEntryTime(context.getSharedPreferences("outbox", Context.MODE_PRIVATE), wallClock = { nowMs })
        .also { it.recordFirstRun() }
        .also { nowMs = NOW_MS }

    /** The adds as Hilt builds them, on `/v4`, telling [outbox] once an upload lands. */
    private fun TestScope.v4Adds(outbox: (() -> Outbox)? = null) = PendingCourseAdds(
        cache,
        pushApiClientAt(
            v4, backend.client, prefs, deviceClocks,
            uploadNews = outbox?.let { get -> CourseUploadListener { _, _, _, signIn -> get().courseUploadLanded(signIn) } },
        ),
        SyncPolicies(prefs), ack, backgroundScope, oldEntryTime,
    )

    private fun field(course: Course, semester: String = term) = SyncField.membership(SyncField.courseKey(semester, course.courseNo))

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun row(course: Course, semester: String = term) =
        """{"semester":"$semester","course_no":"${course.courseNo}","course_name":"${course.courseName}",""" +
            (course.moodleIdNumber?.let { """"moodle_id":"$it",""" } ?: "") +
            """"credits":0.0,"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""

    private fun key(course: Course, semester: String = term) = "client:$semester:${course.courseNo}"

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

    /** An add asked again keeps the later moment, the newer add's. */
    @Test
    fun `an add asked again keeps the later moment`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> throw java.io.IOException("offline") })

        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(6_000L), signIn = signIn)
        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(5_000L), signIn = signIn)
        assertEquals(mapOf(term to mapOf(typed.courseNo to 6_000L)), cache.loadPendingCourseAddTimes())
    }

    /** An add is never given up on for its refusals: every sync sends it again, as before it was a channel. */
    @Test
    fun `an add refused time after time still goes at every sync`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        var refusals = 0
        val adds = PendingCourseAdds(cache, { courses, _, _, _, _ ->
            if (refusals++ < 6) throw PushApiException("uploadCourses failed: HTTP 422", 422)
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        })
        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(5_000L), signIn = signIn)

        repeat(6) { adds.drain(signIn) }

        assertEquals(7, refusals)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * The course added again while its first add's upload is out: the re-add goes at its own turn
     * of the send lock, after a DELETE that was waiting for the lock before it, as it always went.
     */
    @Test
    fun `a re-add made while its first upload is out goes at its own turn of the send lock`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val events = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate: suspend () -> Unit = {}
        gate = {
            gate = {}
            out.complete(Unit)
            release.await()
        }
        val adds = PendingCourseAdds(cache, { courses, _, _, _, addedAt ->
            gate()
            events += "upload ${addedAt.values.single().wallMs}"
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        })

        val first = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(5_000L), signIn = signIn) }
        out.await()
        val delete = launch { adds.removing { events += "DELETE" } }
        runCurrent()
        val again = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(6_000L), signIn = signIn) }
        settle { cache.loadPendingCourseAddTimes()[term]?.get(typed.courseNo) == 6_000L }
        release.complete(Unit)
        first.join()
        delete.join()
        again.join()

        assertEquals(listOf("upload 5000", "DELETE", "upload 6000"), events)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * A caller that leaves while its upload is out does not cut it short, its answer is kept, and
     * the send lock stays held until then, so a DELETE waiting for it does not start beside the
     * upload (spec §5.3).
     */
    @Test
    fun `a caller cancelled during its upload leaves it to finish under the lock, and its answer is stored`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val events = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adds = PendingCourseAdds(cache, { courses, _, _, _, _ ->
            out.complete(Unit)
            release.await()
            events += "upload"
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        })

        val caller = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(5_000L), signIn = signIn) }
        out.await()
        val delete = launch { adds.removing { events += "DELETE" } }
        runCurrent()
        caller.cancelAndJoin()
        runCurrent()
        assertEquals("the DELETE waits for the upload its caller left", emptyList<String>(), events)
        release.complete(Unit)
        delete.join()
        settle { cache.loadPendingCourseAdds().isEmpty() && cache.loadPendingCourseAddTimes().isEmpty() }

        assertEquals(listOf("upload", "DELETE"), events)
    }

    // As an older build left the adds: written before this build first ran, one of them with no time.
    private fun anOlderBuildQueued() {
        val folder = File(context.filesDir, "TigerDuckData").apply { mkdirs() }
        for (name in listOf("pending_course_adds.json", "pending_course_add_times.json")) {
            val fixture = checkNotNull(javaClass.getResource("/account/queues/$name")) { "missing fixture $name" }.readText().trimEnd()
            File(folder, name).apply { writeText(fixture) }.setLastModified(FILE_MS)
        }
    }

    private val one = Course(courseNo = "CS1001", courseName = "One")
    private val two = Course(courseNo = "CS1002", courseName = "Two")

    /**
     * Review Focus 1: an add an older build kept goes out once on `/v4`, aged from its own time,
     * or, with none, from its old-entry time (X1): the queue file's time, which an older build
     * wrote before this build first ran, never the time it is sent. That time is written back at
     * the first load, before anything else writes the file, and stays.
     */
    @Test
    fun `an entry an older build wrote goes out on v4 aged by the wall clock`() = runTest {
        anOlderBuildQueued()
        cache.saveCourses(listOf(one, two), "1141", signIn)
        val adds = v4Adds()
        backend.answer(upload, 500, "{}")

        adds.drain(signIn)
        assertEquals(mapOf("1141" to mapOf("CS1001" to 1_700_000_000_000L, "CS1002" to FILE_MS)), cache.loadPendingCourseAddTimes())

        nowMs += 60_000
        backend.answer(upload, 200, """{"accepted_keys":["client:1141:CS1001","client:1141:CS1002"]}""")
        adds.drain(signIn)

        // Each send reads its ages again: the retry's are a minute older.
        fun body(atMs: Long) = """{"courses":[${row(one, "1141")},${row(two, "1141")}],""" +
            """"adds":[{"course_key":"client:1141:CS1001","age_ms":${atMs - 1_700_000_000_000L}},{"course_key":"client:1141:CS1002","age_ms":${atMs - FILE_MS}}]}"""
        assertEquals(
            listOf(NOW_MS, nowMs).map { listOf("POST", "$v4/sync/courses/upload", listOf(bearer), json, body(it)) },
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

    /** LWW D12: on `/v4` an add is kept while course sync is off, and goes once it is on. */
    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        prefs.syncCourses = false
        val adds = v4Adds()

        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(1_790_000_000_123L), signIn = signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(mapOf(term to mapOf(typed.courseNo to 1_790_000_000_123L)), cache.loadPendingCourseAddTimes())
        assertEquals(setOf(field(typed)), adds.heldFields())

        prefs.syncCourses = true
        backend.answer(upload, 200, """{"accepted_keys":["${key(typed)}"]}""")
        adds.drain(signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v4/sync/courses/upload", listOf(bearer), json,
                    // Made 123 ms past the wall clock now, as no clock reads: an age is never below 0.
                    """{"courses":[${row(typed)}],"adds":[{"course_key":"${key(typed)}","age_ms":0}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /** A refused add is the backend's to settle: a snapshot's tombstone may hide the course since. */
    @Test
    fun `a refused v4 write applies the backend's value unless a newer edit waits`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val adds = v4Adds()
        backend.answer(upload, 200, """{"accepted_keys":[],"adds":[{"course_key":"${key(typed)}","applied":false,"hlc":1790000000999000}]}""")

        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(1_790_000_000_123L), signIn = signIn)

        assertEquals(listOf(typed.courseNo), cache.loadServerKnownNos()[term])
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * The course added again while the first add's upload is out: the refusal answered the older
     * add, and the backend has not yet seen the newer one, so the course is not the backend's to
     * settle yet; the newer add goes next.
     */
    @Test
    fun `a refused add is not the backend's to settle while a newer add of the course waits`() = runTest {
        run {
            val base = v4
            val context = JvmContext()
            val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = base }
            val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
            cache.saveCourses(listOf(typed), term, signIn)
            val out = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var gate: suspend () -> Unit = {}
            gate = {
                gate = {}
                out.complete(Unit)
                release.await()
            }
            val sent = mutableListOf<Long>()
            val adds = PendingCourseAdds(cache, { _, _, _, _, addedAt ->
                sent += addedAt.values.single().wallMs
                gate()
                // The first is refused, a hide elsewhere being newer; the re-add after it lands.
                if (sent.size == 1) CourseUploadAnswer(emptyList(), listOf(typed.courseNo)) else CourseUploadAnswer(listOf(typed.courseNo), emptyList())
            }, MembershipOutbox(SyncPolicies(prefs), scope = backgroundScope))

            val first = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(5_000L), signIn = signIn) }
            out.await()
            val again = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(6_000L), signIn = signIn) }
            settle { cache.loadPendingCourseAddTimes()[term]?.get(typed.courseNo) == 6_000L }
            release.complete(Unit)
            first.join()
            again.join()

            assertEquals(base, listOf(5_000L, 6_000L), sent)
            assertEquals(base, null, cache.loadServerKnownNos()[term])
            assertEquals(base, emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        }
    }

    /** Review Focus 5, on `/v4`: an old entry read under an ended sign-in is not given its time on disk either. */
    @Test
    fun `an entry read under an ended sign-in is neither sent nor rewritten`() = runTest {
        anOlderBuildQueued()
        cache.saveCourses(listOf(one, two), "1141", signIn)
        val adds = v4Adds()
        val folder = File(context.filesDir, "TigerDuckData")
        val before = listOf("pending_course_adds.json", "pending_course_add_times.json").map { File(folder, it).readText() }

        val ended = series.end()
        adds.drain(ended)
        adds.send(listOf(one, two), "1141", "CS1009", addedAt = EditTime(2L), signIn = ended)
        adds.queueAsOldest("1141", "CS1010", ended)
        adds.listed(listOf(org.ntust.app.tigerduck.push.ServerCourse(courseNo = "CS1001", courseName = "One", semester = "1141")), ended)
        adds.forgetAll(ended)
        assertEquals(setOf(field(one, "1141"), field(two, "1141")), adds.heldFields())

        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(before, listOf("pending_course_adds.json", "pending_course_add_times.json").map { File(folder, it).readText() })
    }

    /**
     * A landed upload's clock is each add's acknowledgment, which a pull holds the course by
     * (LWW D12), and the outbox hears of it: an edit that waited for the course goes again.
     * Until then the outbox holds the course; a failed upload tells it nothing.
     */
    @Test
    fun `a landed v4 upload is acknowledged and tells the outbox, which held the course till then`() = runTest {
        cache.saveCourses(listOf(typed, untyped), term, signIn)
        val probe = ProbeChannel()
        lateinit var outbox: Outbox
        val adds = v4Adds { outbox }
        outbox = Outbox(setOf(adds, probe))
        backend.answer(upload, 500, "{}")
        backend.answer(upload, 500, "{}")

        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(1_790_000_000_123L), signIn = signIn)
        adds.send(listOf(untyped), term, untyped.courseNo, addedAt = EditTime(1_790_000_000_124L), signIn = signIn)
        assertTrue(outbox.holds(field(typed)) && outbox.holds(field(untyped)))
        assertEquals(emptyList<SignIn>(), probe.requeued)

        // Both in one upload, each acknowledged at its clock.
        backend.answer(upload, 200, """{"accepted_keys":["${key(typed)}","${key(untyped)}"]}""", mapOf("X-TigerSync-Clock" to "$CLOCK_US"))
        outbox.drain(signIn)

        assertEquals(3, backend.sent.size)
        assertEquals(listOf(CLOCK_US, CLOCK_US), listOf(ack.lastAck(field(typed)), ack.lastAck(field(untyped))))
        assertEquals(listOf(signIn), probe.requeued)
        assertFalse(outbox.holds(field(typed)) || outbox.holds(field(untyped)))
    }

    /** LWW D13's stamp-0 add: kept only where none waits, and sent as the oldest. */
    @Test
    fun `an add queued as the oldest goes as hlc 0, and leaves a waiting add as it is`() = runTest {
        cache.saveCourses(listOf(typed, untyped), term, signIn)
        val adds = v4Adds()
        backend.answer(upload, 500, "{}")
        adds.send(listOf(typed), term, typed.courseNo, addedAt = EditTime(1_790_000_000_123L), signIn = signIn)

        assertTrue(adds.queueAsOldest(term, typed.courseNo, signIn))
        assertTrue(adds.queueAsOldest(term, untyped.courseNo, signIn))
        assertEquals(mapOf(term to mapOf(typed.courseNo to 1_790_000_000_123L, untyped.courseNo to 0L)), cache.loadPendingCourseAddTimes())

        backend.sent.clear()
        adds.drain(signIn)
        assertEquals(
            """{"courses":[${row(typed)},${row(untyped)}],"adds":[{"course_key":"${key(typed)}","age_ms":0},""" +
                """{"course_key":"${key(untyped)}","hlc":0}]}""",
            backend.sent.single().body,
        )
    }

    /** A 409 is a refusal like any other, as `SendResult.afterFailure` reads it, and is not sent again. */
    @Test
    fun `on v4 an add refused with a 409 is a refusal, sent once`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val client = pushApiClientAt(v4, backend.client, prefs, deviceClocks)
        backend.answer(upload, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${NOW_MS * 1_000}"))
        val add = CourseAdd(term, typed.courseNo, 1_790_000_000_123L)
        val sends = CourseAddSends(
            cache,
            { courses, semester, forceKeys, pinned, addedAt -> client.uploadCoursesAnswered(courses, semester, pinned, forceKeys, addedAt = addedAt) },
            MembershipOutbox(SyncPolicies(prefs)),
            waiting = { listOf(add) },
            settle = { _, _ -> true },
        )

        var result: SendResult? = null
        sends.asking(term, listOf(typed.courseNo), null) { result = sends.sendRequested(add, signIn) }

        assertEquals(1, backend.sent.size)
        assertEquals(SendResult.Refused, result)
    }

    /** Another channel, which hears when a course upload has landed. */
    private class ProbeChannel : OutboxChannel {
        override val id = OutboxChannelId.COURSE_OVERRIDES
        val requeued = mutableListOf<SignIn>()
        override suspend fun drain(signIn: SignIn) = Unit
        override suspend fun requeueParked(signIn: SignIn) {
            requeued += signIn
        }
        override suspend fun heldFields(): Set<String> = emptySet()
        override fun forgetMemory() = Unit
    }

    private companion object {
        // An older build last wrote the queue files then, before this build first ran.
        const val FILE_MS = 1_780_000_000_000L
        const val FIRST_RUN_MS = 1_785_000_000_000L
        const val NOW_MS = 1_790_000_000_000L
        const val CLOCK_US = 1_790_000_000_500_000L
    }
}
