package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * An update that finds edits the released build queued, some written before stamps were kept, then
 * the move to `/v4` (Review Focus 1): every entry of today's queue fixtures (`account/queues/`,
 * which `AccountFixturesTest` pins as this build writes them, and the two holiday keys of
 * `account/school-prefs.txt`) goes up once on `/v4`, stamped with its own time, or, with none, the
 * old-entry time (X1): its queue file's time, as an older build wrote it last, or for a holiday
 * this build's first run. Never its send time. The backend's stand-in refuses a write without a
 * stamp as `/v4` does (422 `hlc_required`), and none is refused or dropped. Home's full sync over
 * the app's own clients, queues and synchronizer. The phone is in the term the fixtures name: the
 * color row an older build wrote with no term finds its course key through the current term's
 * course with its Moodle id (PR I).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class V4QueueFixturesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val rules = V4WriteRules()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: AppSyncRig

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // A day of term 1141, the term the fixtures' entries name.
        AppClock.setOverride(ClockOverride(IN_TERM.toInstant(ZoneOffset.ofHours(8)).toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
        val prefs = AppPreferences(context)
        prefs.announcementApiBaseUrlOverride = STORED
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // As the update found them: written by the released build, nothing in this process has touched them.
        val folder = File(context.filesDir, "TigerDuckData").apply { mkdirs() }
        FILE_TIMES.forEach { (name, atMs) ->
            File(folder, name).apply {
                writeText(fixture("queues/$name"))
                assertTrue(setLastModified(atMs))
            }
        }
        // The term as the school's calendar publishes it.
        context.getSharedPreferences("academic_calendar", Context.MODE_PRIVATE).edit()
            .putString("payload", """{"revision":1,"semesters":[{"code":"$TERM","start":"2025-09-01","end":"2026-01-31"}],"holidays":[]}""")
            .commit()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit()
            .putStringSet("holidayNotifyOverrides", setOf("3", "5"))
            .putStringSet("holidayOverridesAwaitingUpload", setOf("5"))
            .commit()
        val clock = SyncClock(SyncClockStore(context), prefs)
        val client = appBackendClient(prefs, clock, backend.client, rules)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client, backendLogin = client)
        // This build's first run, recorded as its process started, after the released build's last write.
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS))
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE, issuedBy = STORED, studentId = STUDENT)
            val signIn = w.sessions.school.signIns.current
            assertEquals(TERM, w.dataCache.currentSemesterCode())
            w.appPrefs.semesterCatalogTerms = listOf(TERM)
            w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
            // The term as the class table holds it: CS1002 added here, CS1003 deleted here.
            assertTrue(w.dataCache.saveCourses(listOf(course("CS1001", "1141CS1001"), course("CS1002", null, manual = true)), TERM, signIn))
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
        AppClock.setOverride(null)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/account/$name")) { "missing fixture $name" }.readText().trimEnd()

    private fun course(courseNo: String, moodleId: String?, manual: Boolean = false) =
        Course(courseNo = courseNo, courseName = "Course $courseNo", moodleIdNumber = moodleId, isManual = manual)

    /** Each write, as `/v4` gets it: method, path and query, token, body. */
    private fun writes(): List<String> = backend.endpoints().indices.mapNotNull { i ->
        val endpoint = backend.endpoints()[i]
        if (endpoint.startsWith("GET ") || endpoint.startsWith("POST /v4/auth/")) return@mapNotNull null
        val request = backend.received(endpoint)[backend.endpoints().subList(0, i).count { it == endpoint }]
        val query = request.query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        "$endpoint$query | ${request.authorization} | ${request.body}"
    }

    @Test
    fun `today's queue fixtures each go out once on v4, stamped, and none is refused`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        answerEachWrite()
        repeat(2) { backend.answer("GET /v4/sync/full", 200, snapshot()) }

        // Home's sync, and the next one: what waits goes in the first, and nothing goes again.
        rig.home.sync.pull(state, signIn)
        rig.home.sync.pull(state, signIn)

        assertEquals(EXPECTED_WRITES, writes())
        assertEquals("refused by /v4's rules", emptyList<String>(), rules.refused)
        assertEquals(
            "every request on /v4",
            emptyList<String>(),
            backend.endpoints().filterNot { it.substringAfter(' ').startsWith("/v4/") },
        )
        // None is dropped unsent, nor left waiting: each was sent and taken.
        assertEquals(emptyMap<String, Any>(), w.dataCache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Any>(), w.dataCache.loadPendingCourseDeletes())
        assertEquals(emptyList<Any>(), w.dataCache.loadPendingCourseOverrides())
        assertEquals(emptyList<Any>(), w.dataCache.loadPendingCourseIgnores())
        assertEquals(emptySet<String>(), rig.home.marks.ids())
        assertEquals(emptySet<Int>(), rig.home.holidays.waitingIds)
    }

    /** The backend takes each write, its reply naming the stamp it was sent with. */
    private fun answerEachWrite() {
        backend.answer(
            "PATCH /v4/sync/courses/client:1141:CS1001/override", 200,
            """{"ignore_assignments":true,"ignore_assignments_hlc":${IGNORE_MS * 1_000},"applied":["ignore_assignments"]}""",
        )
        backend.answer(
            "PATCH /v4/sync/assignments/7/override", 200,
            """{"local_status":"ignored","local_status_hlc":${MARK_MS * 1_000},"applied":true}""",
        )
        backend.answer(
            "POST /v4/sync/courses/upload", 200,
            """{"upserted":2,"skipped_tombstoned":0,"accepted_keys":["client:1141:CS1001","client:1141:CS1002"],""" +
                """"adds":[{"course_key":"client:1141:CS1001","applied":true,"hlc":${ADD_MS * 1_000}},""" +
                """{"course_key":"client:1141:CS1002","applied":true,"hlc":${ADDS_FILE_MS * 1_000}}]}""",
        )
        backend.answer("DELETE /v4/sync/courses/client:1141:CS1003", 200, """{"deleted":1,"applied":true,"hlc":${DELETE_MS * 1_000}}""")
        backend.answer(
            "PATCH /v4/sync/courses/client:1141:CS1001/override", 200,
            """{"color_hex":"#336699","color_hex_hlc":${OVERRIDES_FILE_MS * 1_000},"applied":["color_hex"]}""",
        )
        backend.answer("PUT /v4/sync/holiday-overrides/5", 200, """{"holiday_id":5,"notify":true,"hlc":${FIRST_RUN_MS * 1_000},"applied":true}""")
    }

    /** The term as the backend holds it once the writes landed. */
    private fun snapshot() = JSONObject()
        .put("current_revision", 52)
        .put("server_hlc", FIRST_RUN_MS * 1_000 + 60_000_000)
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray().put(JSONObject().put("moodle_assignment_id", 7).put("local_status", "ignored").put("local_status_hlc", MARK_MS * 1_000)))
        .put(
            "courses",
            JSONArray()
                .put(JSONObject().put("course_key", "client:1141:CS1001").put("course_no", "CS1001").put("course_name", "Course CS1001").put("semester", TERM).put("moodle_id", "1141CS1001"))
                .put(JSONObject().put("course_key", "client:1141:CS1002").put("course_no", "CS1002").put("course_name", "Course CS1002").put("semester", TERM).put("source", "user_added")),
        )
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("course_key", "client:1141:CS1001").put("moodle_id", "1141CS1001").put("course_no", "CS1001").put("semester", TERM)
                    .put("color_hex", "#336699").put("color_hex_hlc", OVERRIDES_FILE_MS * 1_000)
                    .put("ignore_assignments", true).put("ignore_assignments_hlc", IGNORE_MS * 1_000).put("moodle_course_id", 42),
            ),
        )
        .put("course_tombstones", JSONArray())
        .put("holiday_overrides", JSONArray().put(JSONObject().put("holiday_id", 5).put("notify", true).put("hlc", FIRST_RUN_MS * 1_000)))
        .toString()

    private companion object {
        const val STUDENT = "B00015999"
        const val STORED = "http://127.0.0.1:9/v3"
        const val TERM = "1141"
        val IN_TERM: LocalDateTime = LocalDateTime.of(2025, 10, 15, 10, 0)

        // The fixtures' own times.
        const val ADD_MS = 1_700_000_000_000L
        const val DELETE_MS = 1_700_000_001_000L
        const val MARK_MS = 1_700_000_004_000L
        const val IGNORE_MS = 1_700_000_005_000L

        // When the released build last wrote each file, and this build's first run, after them all.
        const val ADDS_FILE_MS = 1_700_000_100_000L
        const val OVERRIDES_FILE_MS = 1_700_000_200_000L
        const val FIRST_RUN_MS = 1_760_000_000_000L
        val FILE_TIMES = mapOf(
            "pending_course_adds.json" to ADDS_FILE_MS,
            "pending_course_add_times.json" to 1_700_000_110_000L,
            "pending_course_deletes.json" to 1_700_000_120_000L,
            "pending_course_overrides.json" to OVERRIDES_FILE_MS,
            "unsent_marks.json" to 1_700_000_130_000L,
            "pending_course_ignores.json" to 1_700_000_140_000L,
        )

        const val ADD_ROW_1 = """{"semester":"1141","course_no":"CS1001","course_name":"Course CS1001","moodle_id":"1141CS1001","credits":0.0,""" +
            """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""
        const val ADD_ROW_2 = """{"semester":"1141","course_no":"CS1002","course_name":"Course CS1002","credits":0.0,""" +
            """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""

        /**
         * Each entry once, in the outbox's order (ignores, marks, adds, deletes, overrides,
         * holidays), each `hlc` its own time in µs or its old-entry time: CS1002's add and the
         * color its queue file's, holiday 5 the first run.
         */
        val EXPECTED_WRITES = listOf(
            "PATCH /v4/sync/courses/client:1141:CS1001/override | Bearer access-1 | " +
                """{"ignore_assignments":true,"moodle_course_id":42,"hlc":1700000005000000}""",
            """PATCH /v4/sync/assignments/7/override | Bearer access-1 | {"local_status":"ignored","hlc":1700000004000000}""",
            "POST /v4/sync/courses/upload | Bearer access-1 | " +
                """{"courses":[$ADD_ROW_1,$ADD_ROW_2],"adds":[{"course_key":"client:1141:CS1001","hlc":1700000000000000},""" +
                """{"course_key":"client:1141:CS1002","hlc":1700000100000000}]}""",
            "DELETE /v4/sync/courses/client:1141:CS1003?hlc=1700000001000000 | Bearer access-1 | ",
            """PATCH /v4/sync/courses/client:1141:CS1001/override | Bearer access-1 | {"color_hex":"#336699","hlc":1700000200000000}""",
            """PUT /v4/sync/holiday-overrides/5 | Bearer access-1 | {"notify":true,"hlc":1760000000000000}""",
        )
    }
}
