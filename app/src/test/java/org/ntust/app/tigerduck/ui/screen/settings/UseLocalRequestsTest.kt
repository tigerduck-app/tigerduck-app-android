package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import kotlinx.coroutines.runBlocking
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
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SchoolUploads
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.TermResets
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * What Use Local for courses sends, request by request, through [PushCourseSyncBackend] as Settings
 * builds it: a term's reset, its upload with its hand-adds and no colors, the deletes of its hidden
 * courses, and each color and name as its course's stamped override, a name the names conflict
 * holds through the course outbox.
 */
class UseLocalRequestsTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val signIn = testSignIn()

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where Use Local is never asked.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @Suppress("UNCHECKED_CAST")
    private fun JSONObject.asMap(): Map<String, Any?> = plain(this) as Map<String, Any?>

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }

    /** Where Use Local's term resets go, as the app's: no school upload waits in these. */
    private fun termResets(cache: DataCache, api: PushApiClient) = TermResets(PendingCourseAdds(cache, api), SchoolUploads.atOnce(cache))

    /** Use Local's backend on `/v4`, what it sends, its course outbox and the cache that keeps it. */
    private class OnV4(
        val courses: PushCourseSyncBackend,
        val backend: RecordingBackend,
        val cache: DataCache,
        val outbox: PendingCourseOverrides,
    ) {
        /** Runs once a term's reset has been answered, for a test to change the phone meanwhile. */
        var afterReset: suspend () -> Unit = {}
    }

    /** The phone holds [courses] in [semester], and [names] (course number, then locale), as Use Local reads them. */
    private suspend fun OnV4.holds(semester: String, courses: List<Course>, names: Map<String, Map<String, String>> = emptyMap()) {
        cache.saveCourses(courses, semester, signIn)
        cache.saveCourseCustomNames(names, signIn)
    }

    /** Built as Settings builds it, with the outbox version reserved before the names are read. */
    private suspend fun onV4(base: String = "http://127.0.0.1:9/v4", setUp: (AppPreferences) -> Unit = {}): OnV4 {
        var built: OnV4? = null
        val v4 = RecordingBackend(
            ahead = listOf(
                Interceptor { chain ->
                    chain.proceed(chain.request()).also {
                        val request = chain.request()
                        if (request.method == "DELETE" && request.url.encodedPath.endsWith("/sync/courses")) runBlocking { built?.afterReset?.invoke() }
                    }
                },
            ),
        )
        val v4Context = JvmContext()
        val v4Prefs = AppPreferences(v4Context).also(setUp)
        val clock = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", 0)), { WALL_MS })
        clock.heardInSnapshot(BACKEND_US)
        val v4Api = pushApiClientAt(base, v4.client, v4Prefs, clock)
        val cache = DataCache(v4Context, AcademicCalendarStore(v4Context, OkHttpClient(), v4Prefs))
        val outbox = PendingCourseOverrides(cache, v4Api, v4Prefs)
        return OnV4(PushCourseSyncBackend(v4Api, v4Prefs, outbox, signIn, outbox.reserveVersion(), cache, termResets(cache, v4Api)), v4, cache, outbox).also { built = it }
    }

    private fun hlcOf(sent: RecordingBackend.Sent): Long? =
        sent.body?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).optLong("hlc").takeIf { hlc -> hlc > 0 } }
            ?: Regex("[?&]hlc=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()

    @Test
    fun `a term whose portal courses are not cached is not reset, and its names are not sent`() = runTest {
        val onV4 = onV4()
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)
        onV4.holds("1151", listOf(manual), mapOf("MY1" to mapOf("en" to "Mine")))

        keepLocalCourses(
            terms = listOf("1151" to listOf(manual)),
            hiding = setOf("1151:CS2"),
            backend = onV4.courses,
            customNames = mapOf("MY1" to mapOf("en" to "Mine")),
            portalCached = { false },
        )

        assertEquals(
            listOf("POST", "DELETE"),
            onV4.backend.sent.map { it.method },
        )
        assertEquals("http://127.0.0.1:9/v4/sync/courses/client:1151:CS2", onV4.backend.sent[1].url.substringBefore('?'))
    }

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

    /**
     * A base that names no API version takes `/v4`'s rules as it takes its requests: each color goes
     * as its course's stamped override, and the hand-added course as an add stamped after the reset,
     * so neither is lost to the reset. Under `/v3`'s rules no color went, and the add went as the oldest.
     */
    @Test
    fun `on a base that names no version Use Local stamps each color override and its hand-adds`() = runTest {
        val onV4 = onV4(base = "http://127.0.0.1:9")
        val (courses, sent) = onV4.courses to onV4.backend
        val colored = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1", customColorHex = "#FF0000")
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true, customColorHex = "#00FF00")
        onV4.holds("1151", listOf(colored, manual))

        keepLocalCourses(terms = listOf("1151" to listOf(colored, manual)), hiding = emptySet(), backend = courses, customNames = emptyMap())

        assertEquals(
            listOf(
                "DELETE http://127.0.0.1:9/sync/courses?semester=1151",
                "POST http://127.0.0.1:9/sync/courses/upload",
                "PATCH http://127.0.0.1:9/sync/courses/client:1151:CS1/override",
                "PATCH http://127.0.0.1:9/sync/courses/client:1151:MY1/override",
            ),
            sent.sent.map { "${it.method} ${it.url.substringBefore("&hlc").substringBefore("?hlc")}" },
        )
        val reset = hlcOf(sent.sent[0])!!
        val add = JSONObject(sent.sent[1].body!!).getJSONArray("adds").getJSONObject(0)
        assertEquals("client:1151:MY1", add.getString("course_key"))
        assertTrue("the add is stamped after the reset", add.getLong("hlc") > reset)
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
        val clock = SyncClock(SyncClockStore(JvmContext().getSharedPreferences("clock", 0)), { WALL_MS })
        val api = PushApiClient(v4.client, v4Prefs, manager, clock)
        val cache = DataCache(v4Context, AcademicCalendarStore(v4Context, OkHttpClient(), v4Prefs))
        val outbox = PendingCourseOverrides(cache, api, v4Prefs)
        val courses = PushCourseSyncBackend(api, v4Prefs, outbox, current, outbox.reserveVersion(), cache, termResets(cache, api))

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
    fun `on v4 nothing goes for a course removed or hidden while the reset was out, not even into the outbox`() = runTest {
        val v4 = onV4 { prefs -> assertTrue(prefs.school.pendingConflictCategories.set(setOf("course_names"), signIn)) }
        val removed = Course(courseNo = "CS1", courseName = "CS1", customColorHex = "#FF0000")
        val hidden = Course(courseNo = "CS2", courseName = "CS2", customColorHex = "#0000FF")
        val names = mapOf("CS1" to mapOf("en" to "One"), "CS2" to mapOf("en" to "Two"))
        v4.holds("1151", listOf(removed, hidden), names)
        // While the reset is out the student removes CS1 from the term and hides CS2; the names stay stored.
        v4.afterReset = {
            v4.cache.saveCourses(listOf(hidden), "1151", signIn)
            v4.cache.saveDeletedCourseNos(setOf("1151:CS2"), signIn)
        }

        keepLocalCourses(listOf("1151" to listOf(removed, hidden)), hiding = emptySet(), backend = v4.courses, customNames = names)

        assertEquals(emptyList<Any>(), v4.overridesSent())
        assertEquals(emptyList<Any>(), v4.cache.loadPendingCourseOverrides())
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
