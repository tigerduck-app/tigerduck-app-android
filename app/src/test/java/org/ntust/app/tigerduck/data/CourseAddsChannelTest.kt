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
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.network.backend.SpeaksV3
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
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
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
 * On `/v3` every request is pinned byte for byte as the queue sent it before it was a channel: its
 * method, address, headers and body, and what each answer leaves queued. On `/v4` (dormant until
 * the flip) the adds an older build kept go stamped (Review Focus 1), an add made with course sync
 * off is kept, a refusal is the backend's to settle unless the course was added again, and a
 * landed upload is acknowledged and tells the outbox.
 */
class CourseAddsChannelTest {

    // Pins /v3's requests and rules until Z1 and Z2 delete them: a base ending in /v3 stays there.
    @get:Rule
    val speaksV3 = SpeaksV3()

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val nextTerm = "1152"
    private val portal = Course(courseNo = "CS1010001", courseName = "Portal", moodleIdNumber = "1151CS1010001")
    private val typed = Course(courseNo = "MA1010002", courseName = "Typed", isManual = true, customColorHex = "#336699")
    private val untyped = Course(courseNo = "PE1010003", courseName = "Untimed", isManual = true)
    private val later = Course(courseNo = "XB0000002", courseName = "Next term", isManual = true)

    private val bearer = "Authorization" to "Bearer access"
    private val protocol2 = "X-TigerSync-Protocol" to "2"
    private val json = "application/json; charset=utf-8"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    private fun v3Adds() = PendingCourseAdds(cache, pushApiClientAt(v3, backend.client, prefs))

    private val v4 = "http://127.0.0.1:9/v4"
    private val upload = "/v4/sync/courses/upload"

    /** The wall clock, which a test moves. */
    private var nowMs = FIRST_RUN_MS
    private val syncClock = SyncClock(SyncClockStore(context.getSharedPreferences("sync-clock", Context.MODE_PRIVATE)), { BackendApi.V4 }, { nowMs })
    private val ack = AckClock()

    // This build first ran at FIRST_RUN_MS, and it is NOW_MS since.
    private val oldEntryTime = OldEntryTime(context.getSharedPreferences("outbox", Context.MODE_PRIVATE), wallClock = { nowMs })
        .also { it.recordFirstRun() }
        .also { nowMs = NOW_MS }

    /** The adds as Hilt builds them, on `/v4`, telling [outbox] once an upload lands. */
    private fun TestScope.v4Adds(outbox: (() -> Outbox)? = null) = PendingCourseAdds(
        cache,
        pushApiClientAt(
            v4, backend.client, prefs, syncClock,
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

    @Test
    fun `on v3 an add goes alone with its time under protocol 2, its color and its force key`() = runTest {
        cache.saveCourses(listOf(portal, typed), term, signIn)
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(typed)}"]}""")

        v3Adds().send(listOf(portal, typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(typed)}],"course_overrides":[{"course_key":"${key(typed)}","color_hex":"#336699"}],""" +
                        """"force_keys":["${key(typed)}"],"adds":[{"course_key":"${key(typed)}","hlc":1790000000123000}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

    /** An add with no time goes with the whole list it was made from, as it always went. */
    @Test
    fun `on v3 an add with no time goes with the list it came from, its force key and no protocol`() = runTest {
        cache.saveCourses(listOf(portal, untyped), term, signIn)

        v3Adds().send(listOf(portal, untyped), term, untyped.courseNo, signIn = signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer), json,
                    """{"courses":[${row(portal)},${row(untyped)}],"course_overrides":[],"force_keys":["${key(untyped)}"]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        // A 200 that names no course taken: kept for the next sync.
        assertEquals(mapOf(term to listOf(untyped.courseNo)), cache.loadPendingCourseAdds())
    }

    /**
     * A sync's retry: each term's timed adds in one upload, then its untimed ones in another, the
     * course as this phone holds it; a term whose upload fails leaves the next one to go.
     */
    @Test
    fun `on v3 a retry sends each term's timed adds, then its untimed ones, and goes on past a failed term`() = runTest {
        cache.saveCourses(listOf(portal, typed, untyped), term, signIn)
        cache.saveCourses(listOf(later), nextTerm, signIn)
        cache.savePendingCourseAdds(mapOf(term to listOf(untyped.courseNo, typed.courseNo), nextTerm to listOf(later.courseNo)), signIn)
        cache.savePendingCourseAddTimes(mapOf(term to mapOf(typed.courseNo to 5_000L), nextTerm to mapOf(later.courseNo to 6_000L)), signIn)
        backend.answer("/v3/sync/courses/upload", 500, "{}")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(untyped)}"]}""")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(later, nextTerm)}"]}""")

        v3Adds().retry(signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(typed)}],"course_overrides":[{"course_key":"${key(typed)}","color_hex":"#336699"}],""" +
                        """"force_keys":["${key(typed)}"],"adds":[{"course_key":"${key(typed)}","hlc":5000000}]}""",
                ),
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer), json,
                    """{"courses":[${row(untyped)}],"course_overrides":[],"force_keys":["${key(untyped)}"]}""",
                ),
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(later, nextTerm)}],"course_overrides":[],"force_keys":["${key(later, nextTerm)}"],""" +
                        """"adds":[{"course_key":"${key(later, nextTerm)}","hlc":6000000}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(mapOf(term to listOf(typed.courseNo)), cache.loadPendingCourseAdds())
        assertEquals(mapOf(term to mapOf(typed.courseNo to 5_000L)), cache.loadPendingCourseAddTimes())
    }

    /**
     * A retry's timed adds go in the order the times file keeps their moments, which is the order
     * each moment was first kept, while the adds file keeps the order each add was: two files that
     * disagree, as an add asked first with no moment and later with one leaves them. The body lists
     * its adds as that file holds them, and its force keys as the adds file does, as before.
     */
    @Test
    fun `on v3 a retry lists its adds in the order their moments were first kept`() = runTest {
        val first = Course(courseNo = "AA1010001", courseName = "First", isManual = true)
        val second = Course(courseNo = "BB1010002", courseName = "Second", isManual = true)
        cache.saveCourses(listOf(first, second), term, signIn)
        val adds = v3Adds()
        repeat(3) { backend.answer("/v3/sync/courses/upload", 500, "{}") }
        // Asked with no moment, then the other with one, then the first again with one; each upload fails.
        adds.send(listOf(first, second), term, first.courseNo, signIn = signIn)
        adds.send(listOf(first, second), term, second.courseNo, addedAt = 5_000L, signIn = signIn)
        adds.send(listOf(first, second), term, first.courseNo, addedAt = 6_000L, signIn = signIn)
        assertEquals(listOf(first.courseNo, second.courseNo), cache.loadPendingCourseAdds()[term])
        assertEquals(listOf(second.courseNo, first.courseNo), cache.loadPendingCourseAddTimes()[term]?.keys?.toList())

        backend.sent.clear()
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(first)}","${key(second)}"]}""")
        adds.retry(signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(first)},${row(second)}],"course_overrides":[],"force_keys":["${key(first)}","${key(second)}"],""" +
                        """"adds":[{"course_key":"${key(second)}","hlc":5000000},{"course_key":"${key(first)}","hlc":6000000}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * A retry reads its adds, their moments and the moments' order in one read of the queue, as it
     * always did under its lock: another sync letting one of them go just after the read
     * (`PendingCourseAdds.listed`) does not move it in the body, which lists the adds as that one
     * read had them.
     */
    @Test
    fun `on v3 a retry lists its adds as its one read had them, one let go just after included`() = runTest {
        val first = Course(courseNo = "AA1010001", courseName = "First", isManual = true)
        val second = Course(courseNo = "BB1010002", courseName = "Second", isManual = true)
        cache.saveCourses(listOf(first, second), term, signIn)
        cache.savePendingCourseAddTimes(mapOf(term to linkedMapOf(first.courseNo to 5_000L, second.courseNo to 6_000L)), signIn)
        cache.savePendingCourseAdds(mapOf(term to listOf(first.courseNo, second.courseNo)), signIn)
        val client = pushApiClientAt(v3, backend.client, prefs)
        var read = false
        val sends = CourseAddSends(
            cache,
            { courses, semester, forceKeys, pinned, addedAt -> client.uploadCoursesAnswered(courses, semester, pinned, forceKeys, addedAt = addedAt) },
            MembershipOutbox(),
            waiting = {
                CourseAddsStore(cache).load().also {
                    if (!read) {
                        read = true
                        // Another sync's snapshot lists the first as enrolled, and lets its add go.
                        cache.savePendingCourseAdds(mapOf(term to listOf(second.courseNo)), signIn)
                        cache.savePendingCourseAddTimes(mapOf(term to mapOf(second.courseNo to 6_000L)), signIn)
                    }
                }
            },
            settle = { _, _ -> true },
        )

        sends.asking(term, listOf(first.courseNo, second.courseNo), null) {
            sends.sendRequested(CourseAdd(term, first.courseNo, 5_000L), signIn)
        }

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(first)},${row(second)}],"course_overrides":[],"force_keys":["${key(first)}","${key(second)}"],""" +
                        """"adds":[{"course_key":"${key(first)}","hlc":5000000},{"course_key":"${key(second)}","hlc":6000000}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
    }

    /** Refused as ahead of the backend's clock, an add goes once more without its time. */
    @Test
    fun `on v3 an add refused as ahead goes again unstamped`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        backend.answer("/v3/sync/courses/upload", 409, """{"detail":"clock_ahead"}""")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(typed)}"]}""")

        v3Adds().send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        val plain = """{"courses":[${row(typed)}],"course_overrides":[{"course_key":"${key(typed)}","color_hex":"#336699"}],""" +
            """"force_keys":["${key(typed)}"]"""
        assertEquals(
            listOf(
                listOf("POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json, "$plain,\"adds\":[{\"course_key\":\"${key(typed)}\",\"hlc\":1790000000123000}]}"),
                listOf("POST", "$v3/sync/courses/upload", listOf(bearer), json, "$plain}"),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /** Nothing goes while course sync is off, and the add is let go: the re-enable check answers for it. */
    @Test
    fun `on v3 an add made with course sync off sends nothing and is let go`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        prefs.syncCourses = false

        v3Adds().send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

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

    /** An add asked again keeps the later moment, the newer add's; one asked again with none has none. */
    @Test
    fun `on v3 an add asked again keeps the later moment, and one asked with none has none`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> throw java.io.IOException("offline") })

        adds.send(listOf(typed), term, typed.courseNo, addedAt = 6_000L, signIn = signIn)
        adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L, signIn = signIn)
        assertEquals(mapOf(term to mapOf(typed.courseNo to 6_000L)), cache.loadPendingCourseAddTimes())

        adds.send(listOf(typed), term, typed.courseNo, signIn = signIn)
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
        assertEquals(mapOf(term to listOf(typed.courseNo)), cache.loadPendingCourseAdds())
    }

    /** An add is never given up on for its refusals: every sync sends it again, as before it was a channel. */
    @Test
    fun `on v3 an add refused time after time still goes at every sync`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        var refusals = 0
        val adds = PendingCourseAdds(cache, { courses, _, _, _, _ ->
            if (refusals++ < 6) throw PushApiException("uploadCourses failed: HTTP 422", 422)
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        })
        adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L, signIn = signIn)

        repeat(6) { adds.retry(signIn) }

        assertEquals(7, refusals)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * The course added again while its first add's upload is out: the re-add goes at its own turn
     * of the send lock, after a DELETE that was waiting for the lock before it, as it always went.
     */
    @Test
    fun `on v3 a re-add made while its first upload is out goes at its own turn of the send lock`() = runTest {
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
            events += "upload ${addedAt.values.single()}"
            CourseUploadAnswer(courses.map { it.courseNo }, emptyList())
        })

        val first = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L, signIn = signIn) }
        out.await()
        val delete = launch { adds.removing { events += "DELETE" } }
        runCurrent()
        val again = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 6_000L, signIn = signIn) }
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

        val caller = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L, signIn = signIn) }
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
     * Review Focus 1: an add an older build kept goes out once on `/v4`, stamped with its own time,
     * or, with none, with its old-entry time (X1): the queue file's time, which an older build
     * wrote before this build first ran, never the time it is sent. That time is written back at
     * the first load, before anything else writes the file, and stays.
     */
    @Test
    fun `an entry an older build wrote goes out on v4 stamped`() = runTest {
        anOlderBuildQueued()
        cache.saveCourses(listOf(one, two), "1141", signIn)
        val adds = v4Adds()
        backend.answer(upload, 500, "{}")

        adds.retry(signIn)
        assertEquals(mapOf("1141" to mapOf("CS1001" to 1_700_000_000_000L, "CS1002" to FILE_MS)), cache.loadPendingCourseAddTimes())

        nowMs += 60_000
        backend.answer(upload, 200, """{"accepted_keys":["client:1141:CS1001","client:1141:CS1002"]}""")
        adds.retry(signIn)

        val body = """{"courses":[${row(one, "1141")},${row(two, "1141")}],""" +
            """"adds":[{"course_key":"client:1141:CS1001","hlc":1700000000000000},{"course_key":"client:1141:CS1002","hlc":${FILE_MS * 1_000}}]}"""
        assertEquals(
            List(2) { listOf("POST", "$v4/sync/courses/upload", listOf(bearer), json, body) },
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

        adds.send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(mapOf(term to mapOf(typed.courseNo to 1_790_000_000_123L)), cache.loadPendingCourseAddTimes())
        assertEquals(setOf(field(typed)), adds.heldFields())

        prefs.syncCourses = true
        backend.answer(upload, 200, """{"accepted_keys":["${key(typed)}"]}""")
        adds.retry(signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v4/sync/courses/upload", listOf(bearer), json,
                    """{"courses":[${row(typed)}],"adds":[{"course_key":"${key(typed)}","hlc":1790000000123000}]}""",
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

        adds.send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        assertEquals(listOf(typed.courseNo), cache.loadServerKnownNos()[term])
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * The course added again while the first add's upload is out: the refusal answered the older
     * add, and the backend has not yet seen the newer one, so the course is not the backend's to
     * settle yet; the newer add goes next. On `/v3` the refusal counts whatever waits, as before.
     */
    @Test
    fun `a refused add is not the backend's to settle while a newer add of the course waits`() = runTest {
        for (base in listOf(v4, v3)) {
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
                sent += addedAt.values.single()
                gate()
                // The first is refused, a hide elsewhere being newer; the re-add after it lands.
                if (sent.size == 1) CourseUploadAnswer(emptyList(), listOf(typed.courseNo)) else CourseUploadAnswer(listOf(typed.courseNo), emptyList())
            }, MembershipOutbox(SyncPolicies(prefs), scope = backgroundScope))

            val first = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L, signIn = signIn) }
            out.await()
            val again = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 6_000L, signIn = signIn) }
            settle { cache.loadPendingCourseAddTimes()[term]?.get(typed.courseNo) == 6_000L }
            release.complete(Unit)
            first.join()
            again.join()

            assertEquals(base, listOf(5_000L, 6_000L), sent)
            assertEquals(base, if (base == v4) null else listOf(typed.courseNo), cache.loadServerKnownNos()[term])
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
        adds.retry(ended)
        adds.send(listOf(one, two), "1141", "CS1009", addedAt = 2L, signIn = ended)
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

        adds.send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)
        adds.send(listOf(untyped), term, untyped.courseNo, addedAt = 1_790_000_000_124L, signIn = signIn)
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
    fun `an add queued as the oldest goes stamped 0, and leaves a waiting add as it is`() = runTest {
        cache.saveCourses(listOf(typed, untyped), term, signIn)
        val adds = v4Adds()
        backend.answer(upload, 500, "{}")
        adds.send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        assertTrue(adds.queueAsOldest(term, typed.courseNo, signIn))
        assertTrue(adds.queueAsOldest(term, untyped.courseNo, signIn))
        assertEquals(mapOf(term to mapOf(typed.courseNo to 1_790_000_000_123L, untyped.courseNo to 0L)), cache.loadPendingCourseAddTimes())

        backend.sent.clear()
        adds.retry(signIn)
        assertEquals(
            """{"courses":[${row(typed)},${row(untyped)}],"adds":[{"course_key":"${key(typed)}","hlc":1790000000123000},""" +
                """{"course_key":"${key(untyped)}","hlc":0}]}""",
            backend.sent.single().body,
        )
    }

    /**
     * Refused as ahead of the backend's clock again after the client's one restamp, an add says
     * nothing about itself: it goes again at the next sync, a retry, not one of its refusals,
     * which `SendResult.afterFailure` would count a 409 as.
     */
    @Test
    fun `on v4 an add refused as ahead once more is a retry, not a refusal`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val client = pushApiClientAt(v4, backend.client, prefs, syncClock)
        repeat(2) { backend.answer(upload, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${NOW_MS * 1_000}")) }
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

        assertEquals(2, backend.sent.size)
        assertEquals(SendResult.Retry, result)
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
