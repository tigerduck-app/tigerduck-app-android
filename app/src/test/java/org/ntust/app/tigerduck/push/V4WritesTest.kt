package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.util.Date

/**
 * Each sync write as `/v4` takes it (`docs/api-v4.md` on tigerduck-backend `main`, and the routes
 * it names): always stamped, a course by its key, a delete with no kind, an upload with its
 * hand-adds and no colors, and no protocol header. The request bodies and the answers are the
 * fixtures in `sync/v4/`, written by hand from the contract. A write refused as ahead of the
 * backend's clock is stamped again from the refusal's clock and sent once (Review Focus 3).
 */
class V4WritesTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val context = JvmContext()
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(context)

    /** The wall clock, which a test moves; every stamp the clock gives is plausible. */
    private var nowMs = 1_790_000_000_000L
    private val clockFile = context.getSharedPreferences("sync-clock", Context.MODE_PRIVATE)
    private val clock = SyncClock(SyncClockStore(clockFile), { nowMs })
    private val client = pushApiClientAt(v4, backend.client, prefs, clock)
    private val signIn = testSignIn()

    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val key = CourseRef(courseKey = "client:1151:CS1")
    private val overridePath = "/v4/sync/courses/client:1151:CS1/override"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    private fun JSONObject.asMap(): Map<String, Any?> =
        keys().asSequence().associateWith { name -> get(name).let { if (it is JSONObject) it.asMap() else if (it == JSONObject.NULL) null else it } }

    private fun json(text: String?): Map<String, Any?>? = text?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).asMap() }

    private fun fixture(name: String) = json(syncFixture("v4/$name"))

    /** Each request as its method, address, headers and body read as JSON. */
    private fun sent() = backend.sent.map { listOf(it.method, it.url, it.headers, json(it.body)) }

    private fun hlcOf(sent: RecordingBackend.Sent): Long? =
        json(sent.body)?.get("hlc")?.let { (it as Number).toLong() } ?: Regex("[?&]hlc=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()

    @Test
    fun `each edit goes as the contract writes it, stamped with the time it was made`() = runTest {
        client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = madeAtMs)
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs)
        client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAtMs = madeAtMs)
        client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAtMs = madeAtMs)
        client.patchCourseOverrideAnswered(key, signIn, customName = "Algo", locale = "en", madeAtMs = madeAtMs)
        client.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = 7, signIn = signIn, madeAtMs = madeAtMs)
        // A school course and one added by hand alike: the backend decides from its row.
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = madeAtMs)
        client.deleteCourseAnswered("client:1151:MY1", signIn, hiddenAt = madeAtMs)
        client.deleteAllCoursesAnswered("1151 x/y", signIn, resetAtMs = madeAtMs)
        client.deleteAllCoursesAnswered(null, signIn, resetAtMs = madeAtMs)

        assertEquals(
            listOf(
                listOf("PUT", "$v4/sync/holiday-overrides/3", listOf(bearer), fixture("write-holiday.json")),
                listOf("PATCH", "$v4/sync/assignments/7/override", listOf(bearer), fixture("write-mark.json")),
                listOf("PATCH", "$v4/sync/assignments/7/override", listOf(bearer), fixture("write-mark-clear.json")),
                listOf("PATCH", "http://127.0.0.1:9$overridePath", listOf(bearer), fixture("write-course-color.json")),
                listOf("PATCH", "http://127.0.0.1:9$overridePath", listOf(bearer), fixture("write-course-name.json")),
                listOf("PATCH", "http://127.0.0.1:9$overridePath", listOf(bearer), fixture("write-course-ignore.json")),
                listOf("DELETE", "$v4/sync/courses/client:1151:CS1?hlc=1790000000123000", listOf(bearer), null),
                listOf("DELETE", "$v4/sync/courses/client:1151:MY1?hlc=1790000000123000", listOf(bearer), null),
                listOf("DELETE", "$v4/sync/courses?semester=1151+x%2Fy&hlc=1790000000123000", listOf(bearer), null),
                listOf("DELETE", "$v4/sync/courses?hlc=1790000000123000", listOf(bearer), null),
            ),
            sent(),
        )
    }

    @Test
    fun `a write with no time is stamped at its send, each later than the last`() = runTest {
        client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = null)
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null)
        client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000")
        client.patchCourseIgnoreAnswered(key, ignore = null, moodleCourseId = 7, signIn = signIn, madeAtMs = null)
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null)
        client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null)

        // The wall clock stood still: the clock's stamps still only grow, a tick at a time.
        assertEquals((1L..6L).map { (nowMs + it) * 1_000 }, backend.sent.map(::hlcOf))
    }

    /** One edit of each kind, on a client of its own, as each is refused on its own. */
    private val eachKind: List<Pair<String, suspend (PushApiClient) -> WriteAnswer>> = listOf(
        "/v4/sync/holiday-overrides/3" to { c -> c.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = null) },
        // A mark queued a minute ahead, as the phone's clock read then.
        "/v4/sync/assignments/7/override" to { c -> c.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = nowMs) },
        overridePath to { c -> c.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000") },
        overridePath to { c -> c.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = null) },
        "/v4/sync/courses/client:1151:CS1" to { c -> c.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null) },
        "/v4/sync/courses" to { c -> c.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null) },
    )

    @Test
    fun `a write refused as ahead is stamped again from the reply's clock and sent once, and the next is capped`() = runTest {
        // A phone a minute ahead that has not heard the backend yet (Review Focus 3).
        val serverMs = nowMs
        nowMs += 60_000
        for ((path, write) in eachKind) {
            val backend = RecordingBackend()
            val clock = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", Context.MODE_PRIVATE)), { nowMs })
            val client = pushApiClientAt(v4, backend.client, AppPreferences(JvmContext()), clock)
            backend.answer(path, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${serverMs * 1_000}"))

            val answer = runCatching { write(client) }
            assertTrue("$path: ${answer.exceptionOrNull()}", answer.getOrNull()?.sent == true)
            client.patchAssignmentOverrideAnswered(8, "ignored", signIn, madeAtMs = null)

            val stamps = backend.sent.map(::hlcOf)
            assertTrue("$path: refused past the cap", stamps[0]!! > (serverMs + 5_000) * 1_000)
            // At the backend's time plus five seconds, plus one tick; the next edit a tick later.
            assertEquals(path, listOf((serverMs + 5_001) * 1_000, (serverMs + 5_002) * 1_000), stamps.drop(1))
        }
    }

    @Test
    fun `a second refusal as ahead is not sent again, and keeps its code`() = runTest {
        val refusal = syncFixture("v4/error-clock-ahead.json")
        repeat(2) { backend.answer("/v4/sync/assignments/7/override", 409, refusal) }

        val failure = runCatching { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs) }.exceptionOrNull()

        assertEquals(BackendError(409, "clock_ahead"), failure?.backendError())
        assertEquals(2, backend.sent.size)
        assertTrue("never without a stamp", backend.sent.all { hlcOf(it) != null })
    }

    @Test
    fun `a refusal as ahead whose gate closed meanwhile goes no further`() = runTest {
        backend.answer(overridePath, 409, syncFixture("v4/error-clock-ahead.json"))
        var checks = 0

        // Asked right before the request, then before the resend: the hold went on in between.
        val answer = client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", allowed = { ++checks == 1 })

        assertEquals(WriteAnswer.NOT_SENT, answer)
        assertEquals(1, backend.sent.size)
    }

    /**
     * A client whose first request to [path] turns [off] while it is out, as the student does in
     * Settings, and is answered `clock_ahead`; what it sent, and the client.
     */
    private fun switchedOffMidRequest(path: String, off: (AppPreferences) -> Unit): Pair<RecordingBackend, PushApiClient> {
        val prefs = AppPreferences(JvmContext())
        val backend = RecordingBackend(
            ahead = listOf(
                Interceptor { chain ->
                    if (chain.request().url.encodedPath == path) off(prefs)
                    chain.proceed(chain.request())
                },
            ),
        )
        backend.answer(path, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${nowMs * 1_000}"))
        val clock = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", Context.MODE_PRIVATE)), { nowMs })
        return backend to pushApiClientAt(v4, backend.client, prefs, clock)
    }

    @Test
    fun `a course write refused as ahead goes no further once course sync went off meanwhile`() = runTest {
        for (off in listOf<(AppPreferences) -> Unit>({ it.syncCourses = false }, { it.cloudSyncEnabled = false })) {
            val (deletes, deleting) = switchedOffMidRequest("/v4/sync/courses/client:1151:CS1", off)
            val (resets, resetting) = switchedOffMidRequest("/v4/sync/courses", off)
            val (uploads, uploading) = switchedOffMidRequest("/v4/sync/courses/upload", off)

            val delete = deleting.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null)
            val reset = resetting.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null)
            val upload = runCatching { uploading.uploadCoursesAnswered(listOf(addedNow), "1151", signIn, addedAt = mapOf("XA0000009" to nowMs)) }

            assertEquals(WriteAnswer.NOT_SENT, delete)
            assertEquals(WriteAnswer.NOT_SENT, reset)
            assertEquals("the upload: ${upload.exceptionOrNull()}", Result.success(null), upload)
            assertEquals(listOf(1, 1, 1), listOf(deletes, resets, uploads).map { it.sent.size })
        }
    }

    @Test
    fun `each answer is read in the contract's shape, and the clock hears every stamp in it`() = runTest {
        backend.answer("/v4/sync/holiday-overrides/3", 200, syncFixture("v4/answer-holiday.json"))
        backend.answer("/v4/sync/assignments/7/override", 200, syncFixture("v4/answer-mark.json"))
        backend.answer(overridePath, 200, syncFixture("v4/answer-course-override.json"))
        backend.answer("/v4/sync/courses/client:1151:CS1", 200, syncFixture("v4/answer-delete.json"))
        backend.answer("/v4/sync/courses", 200, syncFixture("v4/answer-reset.json"))

        val holiday = client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = madeAtMs)
        val mark = client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs)
        val override = client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAtMs = madeAtMs)
        val delete = client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = madeAtMs)
        val reset = client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = madeAtMs)

        assertEquals(setOf("notify"), holiday.applied)
        // The backend kept a later mark, and says which.
        assertEquals(emptySet<String>(), mark.applied)
        assertEquals("archived", mark.values["local_status"])
        assertEquals(1790000000999000L, mark.values["local_status_hlc"])
        assertEquals(setOf("color_hex"), override.applied)
        assertEquals(setOf("membership"), delete.applied)
        assertEquals(setOf("reset"), reset.applied)
        // The largest stamp the answers held, a name's: the next edit is a tick past it.
        client.patchAssignmentOverrideAnswered(8, "ignored", signIn, madeAtMs = null)
        assertEquals((1_790_000_000_999L + 1) * 1_000, hlcOf(backend.sent.last()))
    }

    private val school = Course(courseNo = "XA0000001", courseName = "Fake Course One", moodleIdNumber = "1151XA0000001", customColorHex = "#FF0000")
    private val addedNow = Course(courseNo = "XA0000009", courseName = "Mine", isManual = true, customColorHex = "#00FF00")
    private val addedBefore = Course(courseNo = "XA0000008", courseName = "Mine too", isManual = true)

    @Test
    fun `an upload names every hand-added course in its adds and carries no color`() = runTest {
        backend.answer("/v4/sync/courses/upload", 200, syncFixture("v4/answer-upload.json"))

        val answer = client.uploadCoursesAnswered(
            listOf(school, addedNow, addedBefore), "1151", signIn, rosterTerms = listOf("1151"), addedAt = mapOf("XA0000009" to madeAtMs),
        )

        val sent = backend.sent.single()
        assertEquals(listOf("POST", "$v4/sync/courses/upload", listOf(bearer)), listOf(sent.method, sent.url, sent.headers))
        val body = JSONObject(sent.body!!)
        assertEquals(setOf("courses", "adds", "roster_terms"), body.keys().asSequence().toSet())
        assertEquals(
            """[{"course_key":"client:1151:XA0000009","hlc":1790000000123000},{"course_key":"client:1151:XA0000008","hlc":0}]""",
            body.getJSONArray("adds").toString(),
        )
        assertEquals(listOf("XA0000001", "XA0000009", "XA0000008"), (0 until 3).map { body.getJSONArray("courses").getJSONObject(it).getString("course_no") })
        assertEquals(CourseUploadAnswer(listOf("XA0000001", "XA0000009"), listOf("XA0000008")), answer)
        // The stamps of the adds' answer are heard, the refused one's the largest.
        client.patchAssignmentOverrideAnswered(8, "ignored", signIn, madeAtMs = null)
        assertEquals((1_790_000_000_999L + 1) * 1_000, hlcOf(backend.sent.last()))
    }

    @Test
    fun `a school roster names a hand-added course too, and a forced key, both as the oldest`() = runTest {
        client.uploadCoursesAnswered(listOf(school, addedBefore), "1151", signIn, forceKeys = listOf("client:1151:XA0000001"))

        val body = JSONObject(backend.sent.single().body!!)
        assertTrue("no adds in $body", body.has("adds"))
        assertEquals(
            """[{"course_key":"client:1151:XA0000001","hlc":0},{"course_key":"client:1151:XA0000008","hlc":0}]""",
            body.getJSONArray("adds").toString(),
        )
        assertEquals(setOf("courses", "adds"), body.keys().asSequence().toSet())
    }

    @Test
    fun `an upload with nothing added by hand has no adds`() = runTest {
        client.uploadCoursesAnswered(listOf(school), "1151", signIn)

        assertEquals(setOf("courses"), JSONObject(backend.sent.single().body!!).keys().asSequence().toSet())
    }

    @Test
    fun `an upload refused as ahead goes once more, its timed adds stamped again and the oldest left so`() = runTest {
        val serverMs = nowMs
        nowMs += 60_000
        backend.answer("/v4/sync/courses/upload", 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${serverMs * 1_000}"))

        client.uploadCoursesAnswered(listOf(addedNow, addedBefore), "1151", signIn, addedAt = mapOf("XA0000009" to nowMs))

        assertEquals(2, backend.sent.size)
        assertTrue(backend.sent.all { it.headers == listOf(bearer) })
        assertEquals(
            """[{"course_key":"client:1151:XA0000009","hlc":${(serverMs + 5_001) * 1_000}},{"course_key":"client:1151:XA0000008","hlc":0}]""",
            JSONObject(backend.sent[1].body!!).getJSONArray("adds").toString(),
        )
    }

    @Test
    fun `a course with no key cannot be named on v4, and nothing goes`() = runTest {
        val noKey = CourseRef(courseKey = null)

        val override = runCatching { client.patchCourseOverrideAnswered(noKey, signIn, colorHex = "#FF0000") }.exceptionOrNull()
        val ignore = runCatching { client.patchCourseIgnoreAnswered(noKey, true, null, signIn, madeAtMs = null) }.exceptionOrNull()

        assertTrue("got $override", override is CourseKeyUnknownException)
        assertTrue("got $ignore", ignore is CourseKeyUnknownException)
        assertEquals(emptyList<Any>(), backend.sent)
    }

    @Test
    fun `an assignment upload carries the list as the backend stores it`() = runTest {
        client.uploadAssignments(listOf(Assignment("7", "CS1", "Algorithms", "HW1", Date(0))), signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v4/sync/assignments/upload", listOf(bearer),
                    mapOf(
                        "assignments" to listOf(
                            mapOf(
                                "moodle_assignment_id" to 7, "course_no" to "CS1", "course_name" to "Algorithms", "title" to "HW1",
                                "due_at" to "1970-01-01T00:00:00Z", "is_submitted" to false,
                            ),
                        ),
                    ),
                ),
            ),
            backend.sent.map { listOf(it.method, it.url, it.headers, json(it.body)?.mapValues { (_, v) -> plain(v) }) },
        )
        assertEquals("application/json; charset=utf-8", backend.sent.single().contentType)
    }

    @Test
    fun `a refused write says what failed and its status, and keeps the backend's code`() = runTest {
        val refused = """{"error":"course_not_found","message":"No course has this key."}"""
        listOf(
            "/v4/sync/holiday-overrides/3", "/v4/sync/assignments/7/override", overridePath, overridePath,
            "/v4/sync/courses/client:1151:CS1", "/v4/sync/courses", "/v4/sync/courses/upload",
        ).forEach { backend.answer(it, 404, refused) }

        val failures = listOf(
            runCatching { client.putHolidayOverrideAnswered(3, true, signIn, madeAtMs = null) },
            runCatching { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null) },
            runCatching { client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000") },
            runCatching { client.patchCourseIgnoreAnswered(key, true, null, signIn, madeAtMs = null) },
            runCatching { client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null) },
            runCatching { client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null) },
            runCatching { client.uploadCoursesAnswered(listOf(Course(courseNo = "CS1", courseName = "CS1")), "1151", signIn) },
        ).map { it.exceptionOrNull() }

        assertEquals(
            listOf(
                "holiday override", "patchAssignmentOverride", "patchCourseOverride", "patchCourseIgnore", "deleteCourse",
                "deleteAllCourses", "uploadCourses",
            ).map { "$it failed: HTTP 404" },
            failures.map { it?.message },
        )
        assertEquals(List(7) { BackendError(404, "course_not_found") }, failures.map { it?.backendError() })
    }

    private fun plain(value: Any?): Any? = when (value) {
        is org.json.JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        is JSONObject -> value.asMap().mapValues { (_, v) -> plain(v) }
        else -> value
    }
}
