package org.ntust.app.tigerduck.ui.screen.home

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
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.V4WriteRules
import org.ntust.app.tigerduck.sync.appBackendClient
import org.ntust.app.tigerduck.sync.appSyncRig
import org.ntust.app.tigerduck.sync.oldEntryTimeAt
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The requests Home's full sync sends, in order, as they reach the backend: what waits in each
 * queue, the snapshot, then what the snapshot asks for. Pinned so a change to how the sync is put
 * together sends the same bytes. Over the clients and queues as the app builds them, behind `/v4`'s
 * rules for a write ([V4WriteRules]), a setting the released build left naming `/v3`. Made-up
 * accounts and course numbers; times in a body, which the queues take from the clock, read as
 * `<time>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomePullRequestsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend(under = "/v4")
    private val rules = V4WriteRules()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(listOf(Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = MOODLE_ID))),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        val prefs = AppPreferences(context)
        // The setting as the released build left it.
        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // As the app builds its clients and queues, under /v4's rules for a write.
        val clock = SyncClock(SyncClockStore(context), prefs)
        val client = appBackendClient(prefs, clock, backend.client, rules)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client)
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS)).home
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** Each request after the first [from]: its method and path, token, protocol header and body. */
    private fun sentSince(from: Int): List<String> = backend.endpoints().indices.drop(from).map { i ->
        val endpoint = backend.endpoints()[i]
        val request = backend.received(endpoint).let { all ->
            // The how-many-th request to this endpoint the i-th one is.
            all[backend.endpoints().subList(0, i).count { it == endpoint }]
        }
        val body = request.body.replace(Regex("""\d{12,}"""), "<time>")
        "$endpoint | ${request.authorization} | ${request.protocol} | $body"
    }

    private fun snapshot() = JSONObject()
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put(
            "courses",
            JSONArray().put(
                JSONObject().put("course_no", COURSE).put("course_name", "Kept").put("semester", TERM).put("moodle_id", MOODLE_ID),
            ),
        )
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("moodle_id", MOODLE_ID).put("course_no", COURSE).put("semester", TERM).put("color_hex", "#123456"),
            ),
        )
        .put("current_revision", 42)
        .toString()

    @Test
    fun `a full sync sends what waits, takes the snapshot, then sends what the snapshot asks for`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        w.appPrefs.semesterCatalogTerms = listOf(LOCAL_TERM, TERM)
        val kept = Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = MOODLE_ID)
        val added = Course(courseNo = ADDED, courseName = "Added here", isManual = true)
        w.dataCache.saveCourses(listOf(kept, added), TERM, signIn)
        // A term the server holds nothing of: the sync uploads it.
        w.dataCache.saveCourses(listOf(Course(courseNo = LOCAL, courseName = "Only here")), LOCAL_TERM, signIn)
        // A mark this phone holds, which an empty server takes as this phone's first sync of them.
        w.dataCache.saveIgnoredAssignments(setOf("7"), signIn)

        // One change waiting in each queue, its first send refused where it makes one.
        backend.answer("PATCH /sync/courses/client:$TERM:$COURSE/override", 500)
        rig.ignores.setIgnored(TERM, COURSE, MOODLE_ID, null, ignore = true, signIn = signIn)
        rig.marks.keep("8", "locally_completed", signIn)
        backend.answer("POST /sync/courses", 500)
        rig.adds.send(listOf(kept, added), TERM, ADDED, addedAt = 1L, signIn = signIn)
        assertTrue(rig.deletes.keep(TERM, GONE, queuedAt = 5L, signIn = signIn))
        rig.overrides.queue(
            listOf(PendingCourseOverrides.Change(MOODLE_ID, colorHex = "#ABCDEF", semester = TERM, courseNo = COURSE)),
            rig.overrides.reserveVersion(), signIn,
        )
        backend.answer("PUT /sync/holiday-overrides/$HOLIDAY", 500)
        rig.holidays.send(HOLIDAY, signIn)

        backend.answer("GET /sync/full", 200, snapshot())
        val from = backend.endpoints().size
        rig.sync.pull(state, signIn)

        assertEquals(EXPECTED_PULL, sentSince(from))
    }

    private companion object {
        const val STUDENT = "B00015999"

        /** When this build first ran, which an entry an older build kept with no time is given (X1). */
        const val FIRST_RUN_MS = 1_760_000_000_000L
        const val TERM = "1141"
        const val LOCAL_TERM = "1132"
        const val COURSE = "CS1001"
        const val ADDED = "AD1001"
        const val LOCAL = "LO1001"
        const val GONE = "GO1001"
        const val MOODLE_ID = "1141CS1001"
        const val HOLIDAY = 3

        const val ADD_ROW = """{"semester":"1141","course_no":"AD1001","course_name":"Added here","credits":0.0,""" +
            """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""
        const val LOCAL_ROW = """{"semester":"1132","course_no":"LO1001","course_name":"Only here","credits":0.0,""" +
            """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""

        /** As the sync sends them; see the test. */
        val EXPECTED_PULL = listOf(
            // What waits, in the order the retries run: ignores, marks, adds, deletes, overrides, holidays.
            """PATCH /sync/courses/client:1141:CS1001/override | Bearer access-1 | null | {"ignore_assignments":true,"hlc":<time>}""",
            """PATCH /sync/assignments/8/override | Bearer access-1 | null | {"local_status":"locally_completed","hlc":<time>}""",
            """POST /sync/courses/upload | Bearer access-1 | null | {"courses":[$ADD_ROW],"adds":[{"course_key":"client:1141:AD1001","hlc":1000}]}""",
            "DELETE /sync/courses/client:1141:GO1001 | Bearer access-1 | null | ",
            """PATCH /sync/courses/client:1141:CS1001/override | Bearer access-1 | null | {"color_hex":"#ABCDEF","hlc":<time>}""",
            """PUT /sync/holiday-overrides/3 | Bearer access-1 | null | {"notify":false,"hlc":<time>}""",
            // The snapshot.
            "GET /sync/full | Bearer access-1 | null | ",
            // What it asks for: the marks of a first sync, stamped as they are sent, and the term the
            // server holds nothing of.
            """PATCH /sync/assignments/7/override | Bearer access-1 | null | {"local_status":"ignored","hlc":<time>}""",
            """POST /sync/courses/upload | Bearer access-1 | null | {"courses":[$LOCAL_ROW]}""",
        )
    }
}
