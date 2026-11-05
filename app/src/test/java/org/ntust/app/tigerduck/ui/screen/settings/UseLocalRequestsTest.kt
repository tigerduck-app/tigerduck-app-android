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
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableCourseMerge
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

    /**
     * The device check on PR V: a copy of a school row merged down from the server is manual here,
     * and is no course added by hand. Use Local's upload names the course added here, as an add
     * made now, and lists the copy as the school's, which the term's reset leaves no hide of.
     */
    @Test
    fun `on v4 a term's upload names the course added here, and no copy of a school row merged down`() = runTest {
        val onV4 = onV4()
        val (courses, v4) = onV4.courses to onV4.backend
        // A pull told it its row is the school's.
        val merged = Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "1151CS1", isManual = true, isUserAddedOnServer = false)
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)
        onV4.holds("1151", listOf(merged, manual))

        keepLocalCourses(terms = listOf("1151" to listOf(merged, manual)), hiding = emptySet(), backend = courses)

        val upload = JSONObject(v4.sent.single { it.url.endsWith("/v4/sync/courses/upload") }.body!!)
        val courseNos = upload.getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_no") } }
        assertEquals(listOf("CS1", "MY1"), courseNos)
        val adds = upload.getJSONArray("adds").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_key") } }
        assertEquals(listOf("client:1151:MY1"), adds)
    }

    /**
     * Codex on round 3 (P2): a copy merged down that no `/v4` snapshot has told about, with no rows
     * of the re-enable check to tell it by, goes up as added by hand, an add made now. Unnamed, a
     * `user_added` row would come back from the term's reset as a school row, which a later roster
     * drops from every device.
     */
    @Test
    fun `on v4 a copy no snapshot has told about goes as added by hand when Use Local has no rows to tell it by`() = runTest {
        val onV4 = onV4()
        val (courses, v4) = onV4.courses to onV4.backend
        val older = Course(courseNo = "XE1", courseName = "XE1", moodleIdNumber = "1151XE1", isManual = true)
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)
        onV4.holds("1151", listOf(older, manual))

        keepLocalCourses(terms = listOf("1151" to listOf(older, manual)), hiding = emptySet(), backend = courses)

        assertEquals(listOf("client:1151:MY1", "client:1151:XE1"), addsOf(v4).sorted())
    }

    /** The adds of the one course upload [v4] took. */
    private fun addsOf(v4: RecordingBackend): List<String> =
        JSONObject(v4.sent.single { it.url.endsWith("/v4/sync/courses/upload") }.body!!).getJSONArray("adds")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("course_key") } }

    /**
     * Codex on round 3 (P2), with the rows the re-enable check compared: Use Local tells each copy no
     * snapshot has told about by them before it decides its adds. A copy of a school row goes as the
     * school's, one of a row added by hand as an add made now, and one the snapshot does not list
     * still as added by hand.
     */
    @Test
    fun `on v4 Use Local tells each copy no snapshot has told about by the rows the re-enable check compared`() = runTest {
        val onV4 = onV4()
        val courses = listOf(olderCopy("CS1"), olderCopy("XE1"), olderCopy("XU1"), Course(courseNo = "MY1", courseName = "Mine", isManual = true))
        onV4.holds("1151", courses)

        onV4.useLocal(courses, checkedRows(schoolRow = "CS1", byHandRow = "XE1"))

        assertEquals(listOf("client:1151:MY1", "client:1151:XE1", "client:1151:XU1"), addsOf(onV4.backend).sorted())
    }

    /**
     * Codex on round 5 (P2 #1): what Use Local tells each copy by the check's rows stays on the phone
     * with its upload, and so does a copy it adds now, as added by hand. A copy a stale `false` held,
     * which the check shows as added by hand, would otherwise go unnamed in the next upload before a
     * pull, and the backend would make its row a school row.
     */
    @Test
    fun `on v4 Use Local keeps on the phone what it told each copy, and a copy it adds now as added by hand`() = runTest {
        val onV4 = onV4()
        val stale = olderCopy("XE1").copy(isUserAddedOnServer = false)
        val courses = listOf(olderCopy("CS1"), stale, olderCopy("XU1"), Course(courseNo = "MY1", courseName = "Mine", isManual = true))
        onV4.holds("1151", courses)

        onV4.useLocal(courses, checkedRows(schoolRow = "CS1", byHandRow = "XE1"))

        assertEquals(listOf("client:1151:MY1", "client:1151:XE1", "client:1151:XU1"), addsOf(onV4.backend).sorted())
        assertEquals(
            mapOf("CS1" to false, "XE1" to true, "XU1" to true, "MY1" to null),
            onV4.cache.loadCourses("1151").associate { it.courseNo to it.isUserAddedOnServer },
        )
    }

    /**
     * Codex on round 6 (P2): a school fetch that commits a copy as the school's while Use Local
     * runs keeps its word. Use Local's write of what it told the copy applies only over the value it
     * read (compare-and-set), and its upload takes the copy as the phone holds it then, so it does
     * not name a course the school lists as an add the student never made.
     */
    @Test
    fun `on v4 a school fetch that tells a copy while Use Local runs keeps its word`() = runTest {
        val onV4 = onV4()
        val courses = listOf(olderCopy("XU1"), Course(courseNo = "MY1", courseName = "Mine", isManual = true))
        onV4.holds("1151", courses)
        onV4.afterReset = {
            val fetched = listOf(Course(courseNo = "XU1", courseName = "School XU1", moodleIdNumber = "1151XU1"))
            // Over the term as it stands, under the courses' lock, as `commitFetchedTerm` commits a fetch.
            assertTrue(onV4.cache.updateCourses("1151", signIn) { current, hidden -> ClassTableCourseMerge.mergeFetched(fetched, current, emptyMap(), "zh", hidden) })
        }

        onV4.useLocal(courses, checkedRows(schoolRow = "CS1", byHandRow = "XE1"))

        assertEquals(listOf("client:1151:MY1"), addsOf(onV4.backend))
        assertEquals(mapOf("XU1" to false, "MY1" to null), onV4.cache.loadCourses("1151").associate { it.courseNo to it.isUserAddedOnServer })
    }

    /** A copy merged down as an older build kept it: manual, with a Moodle id, and nothing of its row. */
    private fun olderCopy(no: String) = Course(courseNo = no, courseName = no, moodleIdNumber = "1151$no", isManual = true)

    /** The snapshot the re-enable check compared: 1151's school row [schoolRow] and hand-added row [byHandRow]. */
    private fun checkedRows(schoolRow: String, byHandRow: String) = BackendSyncResult(
        ignoredIds = emptySet(), completedIds = emptySet(), courseOverrides = emptyList(), serverCourseNos = setOf(schoolRow, byHandRow),
        serverCourses = listOf(
            ServerCourse(courseNo = schoolRow, courseName = schoolRow, semester = "1151", moodleId = "1151$schoolRow", source = "ntust_portal"),
            ServerCourse(courseNo = byHandRow, courseName = byHandRow, semester = "1151", moodleId = "1151$byHandRow", source = "user_added"),
        ),
        currentRevision = 1,
    )

    /** Use Local over [courses] in 1151, answered with [checked], as Settings runs it. */
    private suspend fun OnV4.useLocal(courses: List<Course>, checked: BackendSyncResult) {
        val host = object : UseLocalHost {
            override fun beginReset(semester: String) = true
            override fun endReset(semester: String) = Unit
            override suspend fun stampReset(semester: String, atMs: Long) = Unit
            override fun askAgain() = Unit
        }
        useLocalCourses(
            terms = listOf("1151" to courses), hiding = emptySet(), backend = this.courses, customNames = emptyMap(),
            portalCached = { true }, latch = UseLocalLatch(listOf("1151"), host), host = host, checked = checked, isCurrent = { true },
        )
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
