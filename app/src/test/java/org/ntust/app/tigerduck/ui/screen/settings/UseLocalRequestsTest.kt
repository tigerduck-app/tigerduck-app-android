package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * What Use Local for courses sends on `/v3`, request by request, through [PushCourseSyncBackend]
 * as Settings builds it: a term's reset, its upload with the custom colors riding it, the deletes
 * of its hidden courses, and its names through the course outbox. Phase 3 gives Use Local a `/v4`
 * form beside this one; on `/v3` none of it may change.
 */
class UseLocalRequestsTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(context)
    private val api = pushApiClientAt(v3, backend.client, prefs)
    private val signIn = testSignIn()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val overrides = PendingCourseOverrides(cache, api, prefs)

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where Use Local is never asked.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    /** Each request as its method, address and body, a stamp taken from the wall clock read as `<now>`. */
    private fun sent(): List<Triple<String, String, Map<String, Any?>?>> = backend.sent.map { request ->
        val body = request.body?.takeIf { it.isNotEmpty() }?.let { text ->
            val json = JSONObject(text)
            if (json.has("hlc") && request.url.endsWith("/override")) json.put("hlc", "<now>")
            json.asMap()
        }
        Triple(request.method, request.url, body)
    }

    @Suppress("UNCHECKED_CAST")
    private fun JSONObject.asMap(): Map<String, Any?> = plain(this) as Map<String, Any?>

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }

    @Test
    fun `a term is reset, uploaded with its colors, its hidden courses deleted and its names queued, in that order`() = runTest {
        val colored = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1", customColorHex = "#FF0000")
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true, customColorHex = "#00FF00")
        val hidden = Course(courseNo = "CS2", courseName = "CS2")

        keepLocalCourses(
            terms = listOf("1151" to listOf(colored, manual, hidden)),
            hiding = setOf("1151:CS2"),
            backend = PushCourseSyncBackend(api, prefs, overrides, signIn, overrides.reserveVersion(), cache),
            customNames = mapOf("CS1" to mapOf("zh" to "微積分", "en" to "Calc"), "CS2" to mapOf("en" to "Gone")),
        )

        val upload = JSONObject(backend.sent[1].body!!)
        assertEquals(
            listOf(
                Triple("DELETE", "$v3/sync/courses?semester=1151", null),
                // Its body field by field below.
                Triple("POST", "$v3/sync/courses/upload", upload.asMap()),
                Triple("DELETE", "$v3/sync/courses/client:1151:CS2", null),
                Triple("PATCH", "$v3/sync/courses/1151CS1/override", mapOf("custom_name" to "Calc", "locale" to "en", "hlc" to "<now>")),
                Triple("PATCH", "$v3/sync/courses/1151CS1/override", mapOf("custom_name" to "微積分", "locale" to "zh", "hlc" to "<now>")),
            ),
            sent(),
        )
        assertEquals(listOf("CS1", "MY1", "CS2"), (0 until upload.getJSONArray("courses").length()).map { upload.getJSONArray("courses").getJSONObject(it).getString("course_no") })
        assertEquals(
            """[{"course_key":"client:1151:CS1","color_hex":"#FF0000"},{"course_key":"client:1151:MY1","color_hex":"#00FF00"}]""",
            upload.getJSONArray("course_overrides").toString(),
        )
        assertTrue("no adds, no protocol", !upload.has("adds") && backend.sent[1].headers.none { it.first == "X-TigerSync-Protocol" })
    }

    @Test
    fun `a term whose portal courses are not cached is not reset, and its names are not sent`() = runTest {
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)

        keepLocalCourses(
            terms = listOf("1151" to listOf(manual)),
            hiding = setOf("1151:CS2"),
            backend = PushCourseSyncBackend(api, prefs, overrides, signIn, overrides.reserveVersion(), cache),
            customNames = mapOf("MY1" to mapOf("en" to "Mine")),
            portalCached = { false },
        )

        assertEquals(
            listOf("POST", "DELETE"),
            backend.sent.map { it.method },
        )
        assertEquals("$v3/sync/courses/client:1151:CS2", backend.sent[1].url)
    }

    /** A phone whose clock runs behind a backend it has heard: the stamps it gives are past its wall clock. */
    /** Use Local's backend on `/v4`, what it sends, its course outbox and the cache that keeps it. */
    private class OnV4(
        val courses: PushCourseSyncBackend,
        val backend: RecordingBackend,
        val cache: DataCache,
        val outbox: PendingCourseOverrides,
    )

    /** The phone holds [courses] in [semester], and [names] (course number, then locale), as Use Local reads them. */
    private suspend fun OnV4.holds(semester: String, courses: List<Course>, names: Map<String, Map<String, String>> = emptyMap()) {
        cache.saveCourses(courses, semester, signIn)
        cache.saveCourseCustomNames(names, signIn)
    }

    /** Built as Settings builds it, with the outbox version reserved before the names are read. */
    private suspend fun onV4(setUp: (AppPreferences) -> Unit = {}): OnV4 {
        val v4 = RecordingBackend()
        val v4Context = JvmContext()
        val v4Prefs = AppPreferences(v4Context).also(setUp)
        val clock = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", 0)), { BackendApi.V4 }, { WALL_MS })
        clock.heardInSnapshot(BACKEND_US)
        val v4Api = pushApiClientAt("http://127.0.0.1:9/v4", v4.client, v4Prefs, clock)
        val cache = DataCache(v4Context, AcademicCalendarStore(v4Context, OkHttpClient(), v4Prefs))
        val outbox = PendingCourseOverrides(cache, v4Api, v4Prefs)
        return OnV4(PushCourseSyncBackend(v4Api, v4Prefs, outbox, signIn, outbox.reserveVersion(), cache), v4, cache, outbox)
    }

    private fun hlcOf(sent: RecordingBackend.Sent): Long? =
        sent.body?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).optLong("hlc").takeIf { hlc -> hlc > 0 } }
            ?: Regex("[?&]hlc=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()

    @Test
    fun `each color override's stamp is later than the reset's`() = runTest {
        val onV4 = onV4()
        val (courses, v4) = onV4.courses to onV4.backend
        val colored = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1", customColorHex = "#FF0000")
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true, customColorHex = "#00FF00")
        val hidden = Course(courseNo = "CS2", courseName = "CS2", customColorHex = "#0000FF")
        onV4.holds("1151", listOf(colored, manual, hidden), mapOf("CS1" to mapOf("en" to "Calc")))

        keepLocalCourses(
            terms = listOf("1151" to listOf(colored, manual, hidden)),
            hiding = setOf("1151:CS2"),
            backend = courses,
            customNames = mapOf("CS1" to mapOf("en" to "Calc")),
        )

        assertEquals(
            listOf(
                "DELETE http://127.0.0.1:9/v4/sync/courses?semester=1151",
                "POST http://127.0.0.1:9/v4/sync/courses/upload",
                "DELETE http://127.0.0.1:9/v4/sync/courses/client:1151:CS2",
                "PATCH http://127.0.0.1:9/v4/sync/courses/client:1151:CS1/override",
                "PATCH http://127.0.0.1:9/v4/sync/courses/client:1151:CS1/override",
                "PATCH http://127.0.0.1:9/v4/sync/courses/client:1151:MY1/override",
            ),
            v4.sent.map { "${it.method} ${it.url.substringBefore("&hlc").substringBefore("?hlc")}" },
        )
        val upload = JSONObject(v4.sent[1].body!!)
        assertTrue("no colors ride a v4 upload", !upload.has("course_overrides"))
        val reset = hlcOf(v4.sent[0])!!
        val add = upload.getJSONArray("adds").getJSONObject(0)
        assertEquals("client:1151:MY1", add.getString("course_key"))
        val stamps = listOf(reset, add.getLong("hlc")) + v4.sent.drop(2).map { hlcOf(it)!! }
        // Stamped from one clock in the order sent: each later than the reset, though the phone's own
        // clock is behind every one of them, as a stamp from it would be at or before the reset.
        assertEquals(stamps.sorted().distinct(), stamps)
        assertTrue("the reset is past the wall clock", reset > WALL_MS * 1_000)
        assertEquals(
            listOf(mapOf("color_hex" to "#FF0000"), mapOf("custom_name" to "Calc", "locale" to "en"), mapOf("color_hex" to "#00FF00")),
            v4.sent.drop(3).map { sent -> JSONObject(sent.body!!).asMap() - "hlc" },
        )
        assertEquals("a name that went waits for nothing", emptyList<Any>(), onV4.cache.loadPendingCourseOverrides())
    }

    @Test
    fun `on v4 a name waits for the names conflict the student has yet to answer, and the colors still go`() = runTest {
        val onV4 = onV4 { prefs -> assertTrue(prefs.school.pendingConflictCategories.set(setOf("course_names"), signIn)) }
        val (courses, v4) = onV4.courses to onV4.backend
        val colored = Course(courseNo = "CS1", courseName = "CS1", customColorHex = "#FF0000")
        onV4.holds("1151", listOf(colored), mapOf("CS1" to mapOf("en" to "Calc", "zh" to "微積分")))

        keepLocalCourses(
            terms = listOf("1151" to listOf(colored)),
            hiding = emptySet(),
            backend = courses,
            customNames = mapOf("CS1" to mapOf("en" to "Calc", "zh" to "微積分")),
        )

        val overrides = v4.sent.filter { it.url.endsWith("/override") }.map { JSONObject(it.body!!).asMap() - "hlc" }
        assertEquals(listOf(mapOf("color_hex" to "#FF0000")), overrides)
    }

    @Test
    fun `on v4 a name the names conflict holds waits in the course outbox, an older term's too, and nothing sends it`() = runTest {
        val v4 = onV4 { prefs -> assertTrue(prefs.school.pendingConflictCategories.set(setOf("course_names"), signIn)) }
        val older = Course(courseNo = "CS9", courseName = "CS9", customColorHex = "#FF0000")
        v4.holds("1142", listOf(older), mapOf("CS9" to mapOf("en" to "Old name")))

        // The older term is reset, which takes its names off the server with its rows.
        keepLocalCourses(
            terms = listOf("1142" to listOf(older)),
            hiding = emptySet(),
            backend = v4.courses,
            customNames = mapOf("CS9" to mapOf("en" to "Old name")),
        )

        assertEquals(
            "no name goes while the student has yet to answer",
            emptyList<Any>(),
            v4.backend.sent.filter { it.url.endsWith("/override") && "custom_name" in it.body.orEmpty() },
        )
        // Kept as /v3 keeps it, by the Moodle id the upload stored the row under, for the hold's answer to send.
        assertEquals(
            listOf(Triple("1142CS9", "Old name", "en")),
            v4.cache.loadPendingCourseOverrides().map { Triple(it.moodleId, it.customName, it.locale) },
        )
    }

    @Test
    fun `on v4 a held name read before a rename does not replace the rename waiting in the outbox`() = runTest {
        // The version is reserved, and the names read, as Use Local starts.
        val v4 = onV4 { prefs -> assertTrue(prefs.school.pendingConflictCategories.set(setOf("course_names"), signIn)) }
        // While the reset and the upload are out, the student renames the course: held, it waits.
        v4.outbox.send("1142CS9", customName = "New name", locale = "en", signIn = signIn)

        v4.courses.patchStampedOverride("1142", "CS9", "1142CS9", name = "Old name", locale = "en")

        assertEquals(
            listOf(Triple("1142CS9", "New name", "en")),
            v4.cache.loadPendingCourseOverrides().map { Triple(it.moodleId, it.customName, it.locale) },
        )
    }

    @Test
    fun `on v4 a held name is not queued once course sync went off while its request was made`() = runTest {
        val v4Context = JvmContext()
        val v4Prefs = AppPreferences(v4Context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v4" }
        // The session's token has run out: the request renews it first, and course sync goes off meanwhile.
        val v4 = RecordingBackend(
            ahead = listOf(
                Interceptor { chain ->
                    if (chain.request().url.encodedPath.endsWith("/auth/refresh")) v4Prefs.syncCourses = false
                    chain.proceed(chain.request())
                },
            ),
        )
        v4.answer("/v4/auth/refresh", 200, """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":900,"device_id":"device-1"}""")
        val tokens = V3TokenStore(v4Context.getSharedPreferences("v3-tokens-expired", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", 0L)
        val manager = AuthTokenManager(tokens, v4.client, v4Prefs, "device-1")
        val current = manager.session.signIns.current
        assertTrue(v4Prefs.school.pendingConflictCategories.set(setOf("course_names"), current))
        val clock = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", 0)), { BackendApi.V4 }, { WALL_MS })
        val api = PushApiClient(v4.client, v4Prefs, manager, clock)
        val cache = DataCache(v4Context, AcademicCalendarStore(v4Context, OkHttpClient(), v4Prefs))
        val outbox = PendingCourseOverrides(cache, api, v4Prefs)
        val courses = PushCourseSyncBackend(api, v4Prefs, outbox, current, outbox.reserveVersion(), cache)

        val thrown = runCatching { courses.patchStampedOverride("1142", "CS9", "1142CS9", name = "Old name", locale = "en") }.exceptionOrNull()

        assertEquals(listOf("/v4/auth/refresh"), v4.sent.map { it.url.substringAfter("127.0.0.1:9") })
        assertTrue("got $thrown", thrown is CourseSyncOffException)
        assertEquals(emptyList<Any>(), cache.loadPendingCourseOverrides())
    }

    /** What Use Local read at its start: the course in red, named Old in English. */
    private val asRead = Course(courseNo = "CS1", courseName = "CS1", customColorHex = "#FF0000")

    private suspend fun useLocalAsRead(v4: OnV4) = keepLocalCourses(
        terms = listOf("1151" to listOf(asRead)),
        hiding = emptySet(),
        backend = v4.courses,
        customNames = mapOf("CS1" to mapOf("en" to "Old")),
    )

    private fun OnV4.overridesSent() = backend.sent.filter { it.url.endsWith("/override") }.map { JSONObject(it.body!!).asMap() - "hlc" }

    @Test
    fun `on v4 a color changed after Use Local read it goes as it is when its override goes`() = runTest {
        val v4 = onV4()
        v4.holds("1151", listOf(asRead.copy(customColorHex = "#00FF00")), mapOf("CS1" to mapOf("en" to "Old")))

        useLocalAsRead(v4)

        assertEquals(listOf(mapOf("color_hex" to "#00FF00"), mapOf("custom_name" to "Old", "locale" to "en")), v4.overridesSent())
    }

    @Test
    fun `on v4 a name changed after Use Local read it goes as it is when its override goes`() = runTest {
        val v4 = onV4()
        v4.holds("1151", listOf(asRead), mapOf("CS1" to mapOf("en" to "New")))

        useLocalAsRead(v4)

        assertEquals(listOf(mapOf("color_hex" to "#FF0000"), mapOf("custom_name" to "New", "locale" to "en")), v4.overridesSent())
    }

    @Test
    fun `on v4 a color cleared after Use Local read it goes not at all`() = runTest {
        val v4 = onV4()
        v4.holds("1151", listOf(asRead.copy(customColorHex = null)), mapOf("CS1" to mapOf("en" to "Old")))

        useLocalAsRead(v4)

        assertEquals(listOf(mapOf("custom_name" to "Old", "locale" to "en")), v4.overridesSent())
    }

    @Test
    fun `on v4 a held name changed after Use Local read it waits in the outbox as it is now`() = runTest {
        val v4 = onV4 { prefs -> assertTrue(prefs.school.pendingConflictCategories.set(setOf("course_names"), signIn)) }
        v4.holds("1151", listOf(asRead), mapOf("CS1" to mapOf("en" to "New")))

        useLocalAsRead(v4)

        assertEquals(listOf("New"), v4.cache.loadPendingCourseOverrides().map { it.customName })
    }

    @Test
    fun `on v4 a color for a course the backend does not hold is let go, and any other refusal asks again`() = runTest {
        val onV4 = onV4()
        val (courses, v4) = onV4.courses to onV4.backend
        val terms = listOf("1151" to listOf(Course(courseNo = "CS1", courseName = "CS1", customColorHex = "#FF0000")))
        onV4.holds("1151", terms.single().second)
        val override = "/v4/sync/courses/client:1151:CS1/override"
        v4.answer(override, 404, """{"error":"course_not_found","message":"No such course."}""")
        v4.answer(override, 500, """{"error":"internal_error","message":"Internal server error."}""")

        val gone = runCatching { keepLocalCourses(terms, hiding = emptySet(), backend = courses) }.exceptionOrNull()
        val failure = runCatching { keepLocalCourses(terms, hiding = emptySet(), backend = courses) }.exceptionOrNull()

        assertEquals(null, gone)

        assertTrue("got $failure", failure != null && failure !is CourseSyncOffException)
        assertEquals(6, v4.sent.size)
    }

    private companion object {
        /** The phone's wall clock, and the backend's time it heard, ten minutes later. */
        const val WALL_MS = 1_790_000_000_000L
        const val BACKEND_US = (WALL_MS + 600_000) * 1_000
    }
}
