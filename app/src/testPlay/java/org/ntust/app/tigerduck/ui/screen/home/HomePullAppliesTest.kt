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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What Home's full sync takes from the snapshot into what Home shows: the marks, recorded as taken
 * once for the account; the ignored courses; and the colors, only while their switch is on and no
 * re-enable check holds them. Also the conflict dialog's "keep the server's". Made-up accounts and
 * course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomePullAppliesTest {

    // Pins /v3's requests and rules until Z1 and Z2 delete them: a base ending in /v3 stays there.
    @get:Rule
    val speaksV3 = SpeaksV3()

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
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
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        rig = homeSyncRig(context, w, backend.client, idleScope)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun snapshot() = JSONObject()
        .put("assignments", JSONArray().put(JSONObject().put("id", 1).put("moodle_assignment_id", 7)))
        .put("assignment_overrides", JSONArray().put(JSONObject().put("moodle_assignment_id", 7).put("local_status", "ignored")))
        .put(
            "courses",
            JSONArray().put(
                JSONObject().put("course_no", COURSE).put("course_name", "Kept").put("semester", TERM).put("moodle_id", MOODLE_ID),
            ),
        )
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("moodle_id", MOODLE_ID).put("course_no", COURSE).put("semester", TERM).put("color_hex", "#123456")
                    .put("ignore_assignments", true).put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
            ),
        )
        .put("current_revision", 42)
        .toString()

    @Test
    fun `a pull shows the snapshot's marks and ignored courses, and records the marks as taken once`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        backend.answer("GET /sync/full", 200, snapshot())

        rig.sync.pull(state, signIn)

        assertEquals(setOf("7"), state.ignoredAssignmentIds.value)
        assertTrue("the marks are not recorded as taken", w.appPrefs.school.assignmentMarksSyncedOnce.get())
        assertEquals(setOf("$TERM:$COURSE"), state.ignoredCourseKeys.value)
    }

    @Test
    fun `the server's color lands only while colors are switched on and not held`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        w.appPrefs.school.pendingConflictCategories.set(setOf("course_colors"), signIn)
        backend.answer("GET /sync/full", 200, snapshot())
        rig.sync.pull(state, signIn)
        assertEquals("held for the re-enable check", listOf<String?>(null), state.allCourses.value.map { it.customColorHex })

        w.appPrefs.school.pendingConflictCategories.set(emptySet(), signIn)
        w.appPrefs.syncCourseColors = false
        backend.answer("GET /sync/full", 200, snapshot())
        rig.sync.pull(state, signIn)
        assertEquals("switched off", listOf<String?>(null), state.allCourses.value.map { it.customColorHex })

        w.appPrefs.syncCourseColors = true
        backend.answer("GET /sync/full", 200, snapshot())
        rig.sync.pull(state, signIn)
        assertEquals(listOf<String?>("#123456"), state.allCourses.value.map { it.customColorHex })
    }

    /** The conflict dialog's "keep the server's": the snapshot that was contested, marks and colors. */
    @Test
    fun `Use Server takes the contested snapshot's marks and colors`() = runBlocking {
        val signIn = w.sessions.school.signIns.current
        val contested = BackendSyncResult(
            ignoredIds = setOf("7"),
            completedIds = emptySet(),
            courseOverrides = listOf(CourseOverrideResult(moodleCourseId = MOODLE_ID, courseNo = COURSE, colorHex = "#123456", semester = TERM)),
            serverCourseNos = setOf(COURSE),
            currentRevision = 42,
        )

        rig.sync.applyResolution(state, contested, emptyList(), keepLocal = false, signIn = signIn)

        assertEquals(setOf("7"), state.ignoredAssignmentIds.value)
        assertEquals(listOf<String?>("#123456"), state.allCourses.value.map { it.customColorHex })
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val TERM = "1141"
        const val COURSE = "CS1001"
        const val MOODLE_ID = "1141CS1001"
    }
}
