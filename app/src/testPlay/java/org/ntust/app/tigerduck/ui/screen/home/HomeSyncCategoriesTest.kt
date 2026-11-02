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
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What Home's full sync records as synced (`syncedCategories`), for every combination of the four
 * category switches and of the re-enable check's hold: a category whose switch is on and that no
 * check holds, as long as the colors and names had a course list to land on. Pinned before the
 * sync's steps moved into the `Synchronizer`, and held after. Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeSyncCategoriesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig

    private fun state(courses: List<Course>) = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(courses),
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

    /** A snapshot with something of every category: a mark, the term's course, and its color. */
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
                JSONObject().put("moodle_id", MOODLE_ID).put("course_no", COURSE).put("semester", TERM).put("color_hex", "#123456"),
            ),
        )
        .put("current_revision", 42)
        .toString()

    /** What one full sync records with [switchedOn] on and [held] held, over [courses] on Home. */
    private suspend fun recorded(switchedOn: Set<String>, held: Set<String>, courses: List<Course>): Set<String> {
        val signIn = w.sessions.school.signIns.current
        w.appPrefs.syncCourses = "courses" in switchedOn
        w.appPrefs.syncCourseColors = "course_colors" in switchedOn
        w.appPrefs.syncCourseNames = "course_names" in switchedOn
        w.appPrefs.syncAssignments = "assignments" in switchedOn
        w.appPrefs.school.pendingConflictCategories.set(held, signIn)
        w.appPrefs.school.syncedCategories.set(emptySet(), signIn)
        backend.answer("GET /sync/full", 200, snapshot())
        rig.sync.pull(state(courses), signIn)
        return w.appPrefs.school.syncedCategories.get()
    }

    private val subsets: List<Set<String>> = (0 until (1 shl CATEGORIES.size)).map { bits ->
        CATEGORIES.filterIndexedTo(LinkedHashSet()) { i, _ -> bits and (1 shl i) != 0 }
    }

    private fun everyCombination(courses: List<Course>, expected: (switchedOn: Set<String>, held: Set<String>) -> Set<String>) = runBlocking {
        val wrong = mutableListOf<String>()
        for (switchedOn in subsets) {
            for (held in subsets) {
                val got = recorded(switchedOn, held, courses)
                val want = expected(switchedOn, held)
                if (got != want) wrong += "on=$switchedOn held=$held: recorded $got, expected $want"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a sync records each category switched on and not held`() = everyCombination(
        listOf(Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = MOODLE_ID)),
    ) { switchedOn, held -> switchedOn - held }

    @Test
    fun `colors and names are not recorded while Home has no course list to put them on`() = everyCombination(emptyList()) { switchedOn, held ->
        switchedOn - held - "course_colors" - "course_names"
    }

    private companion object {
        val CATEGORIES = listOf("courses", "course_colors", "course_names", "assignments")
        const val STUDENT = "B00015999"
        const val TERM = "1141"
        const val COURSE = "CS1001"
        const val MOODLE_ID = "1141CS1001"
    }
}
