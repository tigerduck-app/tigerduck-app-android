package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
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
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.util.Date

/**
 * Each sync write as `/v4` takes it (`docs/api-v4.md` on tigerduck-backend `main`, and the routes
 * it names): always with its age (`age_ms`, backend 4.3.0), never an `hlc`, a course by its key, a
 * delete with no kind, an upload with its hand-adds and no colors, and no protocol header. The
 * request bodies and the answers are the fixtures in `sync/v4/`, written by hand from the contract.
 */
class V4WritesTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val context = JvmContext()
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(context)

    /** The device's clocks, which a test moves: the wall clock, the elapsed clock and the boot. */
    private var nowMs = 1_790_000_000_000L
    private var elapsedMs = 9_000_000L
    private val clock = DeviceClocks({ nowMs }, { elapsedMs }, { BOOT })
    private val client = pushApiClientAt(v4, backend.client, prefs, clock)
    private val signIn = testSignIn()

    /** An edit made 2.5 s ago by the elapsed clock, in this boot; the wall clock says otherwise. */
    private val madeAt = EditTime(wallMs = 1_790_000_000_123L, elapsedMs = elapsedMs - 2_500, boot = BOOT)
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

    // The request's age (`age_ms`), from its body or its query.
    private fun ageOf(sent: RecordingBackend.Sent): Long? =
        json(sent.body)?.get("age_ms")?.let { (it as Number).toLong() } ?: Regex("[?&]age_ms=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()

    @Test
    fun `each edit goes as the contract writes it, with its age by the elapsed clock and no hlc`() = runTest {
        client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAt = madeAt)
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = madeAt)
        // Through a client of its own: this one, the mark above answered since it was made, would
        // count its age from that answer (AnswerArrivalWritesTest).
        pushApiClientAt(v4, backend.client, prefs, clock).patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = madeAt)
        client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAt = madeAt)
        client.patchCourseOverrideAnswered(key, signIn, customName = "Algo", locale = "en", madeAt = madeAt)
        client.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = 7, signIn = signIn, madeAt = madeAt)
        // A school course and one added by hand alike: the backend decides from its row.
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = madeAt)
        client.deleteCourseAnswered("client:1151:MY1", signIn, hiddenAt = madeAt)
        client.deleteAllCoursesAnswered("1151 x/y", signIn, resetAt = madeAt)
        client.deleteAllCoursesAnswered(null, signIn, resetAt = madeAt)

        assertEquals(
            listOf(
                listOf("PUT", "$v4/sync/holiday-overrides/3", listOf(bearer), fixture("write-holiday.json")),
                listOf("PATCH", "$v4/sync/assignments/7/override", listOf(bearer), fixture("write-mark.json")),
                listOf("PATCH", "$v4/sync/assignments/7/override", listOf(bearer), fixture("write-mark-clear.json")),
                listOf("PATCH", "http://127.0.0.1:9$overridePath", listOf(bearer), fixture("write-course-color.json")),
                listOf("PATCH", "http://127.0.0.1:9$overridePath", listOf(bearer), fixture("write-course-name.json")),
                listOf("PATCH", "http://127.0.0.1:9$overridePath", listOf(bearer), fixture("write-course-ignore.json")),
                listOf("DELETE", "$v4/sync/courses/client:1151:CS1?age_ms=2500", listOf(bearer), null),
                listOf("DELETE", "$v4/sync/courses/client:1151:MY1?age_ms=2500", listOf(bearer), null),
                listOf("DELETE", "$v4/sync/courses?semester=1151+x%2Fy&age_ms=2500", listOf(bearer), null),
                listOf("DELETE", "$v4/sync/courses?age_ms=2500", listOf(bearer), null),
            ),
            sent(),
        )
    }

    @Test
    fun `a write made now, with no time kept, goes 0 old`() = runTest {
        client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAt = null)
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null)
        client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000")
        client.patchCourseIgnoreAnswered(key, ignore = null, moodleCourseId = 7, signIn = signIn, madeAt = null)
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null)
        client.deleteAllCoursesAnswered("1151", signIn, resetAt = null)

        assertEquals(List(6) { 0L }, backend.sent.map(::ageOf))
    }

    @Test
    fun `a retry sends the age grown since, read again at its send`() = runTest {
        backend.answer("/v4/sync/assignments/7/override", 503, "")

        runCatching { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = madeAt) }
        elapsedMs += 60_000
        nowMs -= 3_600_000
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = madeAt)

        assertEquals("by the elapsed clock, whatever the wall clock did", listOf(2_500L, 62_500L), backend.sent.map(::ageOf))
    }

    @Test
    fun `an edit from another boot, or an older build's, ages by the wall clock`() = runTest {
        val otherBoot = madeAt.copy(boot = BOOT - 1)
        val olderBuild = EditTime(madeAt.wallMs)

        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = otherBoot)
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = olderBuild)

        // The wall clock reads 123 ms before the edit's: an age is never below 0.
        assertEquals(listOf(0L, 0L), backend.sent.map(::ageOf))
        nowMs += 10_000
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = olderBuild)
        assertEquals(10_000L - 123, ageOf(backend.sent.last()))
    }

    @Test
    fun `a second refusal is answered as any refusal, its code kept, and never sent again`() = runTest {
        val refusal = syncFixture("v4/error-clock-ahead.json")
        repeat(2) { backend.answer("/v4/sync/assignments/7/override", 409, refusal) }

        val failure = runCatching { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = madeAt) }.exceptionOrNull()

        assertEquals(BackendError(409, "clock_ahead"), failure?.backendError())
        assertEquals(1, backend.sent.size)
    }

    @Test
    fun `each answer is read in the contract's shape`() = runTest {
        backend.answer("/v4/sync/holiday-overrides/3", 200, syncFixture("v4/answer-holiday.json"))
        backend.answer("/v4/sync/assignments/7/override", 200, syncFixture("v4/answer-mark.json"))
        backend.answer(overridePath, 200, syncFixture("v4/answer-course-override.json"))
        backend.answer("/v4/sync/courses/client:1151:CS1", 200, syncFixture("v4/answer-delete.json"))
        backend.answer("/v4/sync/courses", 200, syncFixture("v4/answer-reset.json"))

        val holiday = client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAt = madeAt)
        val mark = client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = madeAt)
        val override = client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAt = madeAt)
        val delete = client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = madeAt)
        val reset = client.deleteAllCoursesAnswered("1151", signIn, resetAt = madeAt)

        assertEquals(setOf("notify"), holiday.applied)
        // The backend kept a later mark, and says which.
        assertEquals(emptySet<String>(), mark.applied)
        assertEquals("archived", mark.values["local_status"])
        assertEquals(1790000000999000L, mark.values["local_status_hlc"])
        assertEquals(setOf("color_hex"), override.applied)
        assertEquals(setOf("membership"), delete.applied)
        assertEquals(setOf("reset"), reset.applied)
    }

    private val school = Course(courseNo = "XA0000001", courseName = "Fake Course One", moodleIdNumber = "1151XA0000001", customColorHex = "#FF0000")
    private val addedNow = Course(courseNo = "XA0000009", courseName = "Mine", isManual = true, customColorHex = "#00FF00")
    private val addedBefore = Course(courseNo = "XA0000008", courseName = "Mine too", isManual = true)

    @Test
    fun `an upload names every hand-added course in its adds and carries no color`() = runTest {
        backend.answer("/v4/sync/courses/upload", 200, syncFixture("v4/answer-upload.json"))

        val answer = client.uploadCoursesAnswered(
            listOf(school, addedNow, addedBefore), "1151", signIn, rosterTerms = listOf("1151"), addedAt = mapOf("XA0000009" to madeAt),
        )

        val sent = backend.sent.single()
        assertEquals(listOf("POST", "$v4/sync/courses/upload", listOf(bearer)), listOf(sent.method, sent.url, sent.headers))
        val body = JSONObject(sent.body!!)
        assertEquals(setOf("courses", "adds", "roster_terms"), body.keys().asSequence().toSet())
        assertEquals(
            // The add with no time as the oldest: hlc 0, which loses to every stamp the backend holds.
            listOf(mapOf("course_key" to "client:1151:XA0000009", "age_ms" to 2500), mapOf("course_key" to "client:1151:XA0000008", "hlc" to 0)),
            plain(body.getJSONArray("adds")),
        )
        assertEquals(listOf("XA0000001", "XA0000009", "XA0000008"), (0 until 3).map { body.getJSONArray("courses").getJSONObject(it).getString("course_no") })
        assertEquals(CourseUploadAnswer(listOf("XA0000001", "XA0000009"), listOf("XA0000008")), answer)
    }

    @Test
    fun `a school roster names a hand-added course too, and a forced key, both as the oldest`() = runTest {
        client.uploadCoursesAnswered(listOf(school, addedBefore), "1151", signIn, forceKeys = listOf("client:1151:XA0000001"))

        val body = JSONObject(backend.sent.single().body!!)
        assertTrue("no adds in $body", body.has("adds"))
        assertEquals(
            listOf(mapOf("course_key" to "client:1151:XA0000001", "hlc" to 0), mapOf("course_key" to "client:1151:XA0000008", "hlc" to 0)),
            plain(body.getJSONArray("adds")),
        )
        assertEquals(setOf("courses", "adds"), body.keys().asSequence().toSet())
    }

    @Test
    fun `an upload with nothing added by hand has no adds`() = runTest {
        client.uploadCoursesAnswered(listOf(school), "1151", signIn)

        assertEquals(setOf("courses"), JSONObject(backend.sent.single().body!!).keys().asSequence().toSet())
    }

    @Test
    fun `a course with no key cannot be named on v4, and nothing goes`() = runTest {
        val noKey = CourseRef(courseKey = null)

        val override = runCatching { client.patchCourseOverrideAnswered(noKey, signIn, colorHex = "#FF0000") }.exceptionOrNull()
        val ignore = runCatching { client.patchCourseIgnoreAnswered(noKey, true, null, signIn, madeAt = null) }.exceptionOrNull()

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
            runCatching { client.putHolidayOverrideAnswered(3, true, signIn, madeAt = null) },
            runCatching { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) },
            runCatching { client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000") },
            runCatching { client.patchCourseIgnoreAnswered(key, true, null, signIn, madeAt = null) },
            runCatching { client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null) },
            runCatching { client.deleteAllCoursesAnswered("1151", signIn, resetAt = null) },
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

    private companion object {
        const val BOOT = 7
    }
}
