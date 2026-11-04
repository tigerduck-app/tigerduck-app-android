package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.network.backend.SpeaksV3
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The same student signs out and back in while the background worker's pass has its fetch out,
 * as `HomePullAcrossSignInTest` drives Home's: nothing of the old pass lands, on disk, in the
 * preferences or in the first-pull gate, it says nothing of the backend for the next sign-in,
 * and a refused session past the sign-out signs nobody in. The real synchronizer in background
 * mode over the real stores, with the backend scripted. Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackgroundPullAcrossSignInTest {

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

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        rig = homeSyncRig(context, w, backend.client, idleScope)
        signIn()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun signIn() = runBlocking {
        assertTrue(w.auth.login(STUDENT, "made-up-password"))
        w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
    }

    /** The whole sign-out, wipe included, and the same student back. */
    private suspend fun signOutAndBackIn() {
        w.plans.signOut(SignOutReason.SCHOOL)
        withTimeout(10_000) { w.sessions.school.awaitOpen() }
        signIn()
    }

    /** Something for every background step to take: marks, an ignore, a color, the term, a reset elsewhere. */
    private fun snapshot(term: String) = JSONObject()
        .put("assignments", JSONArray().put(JSONObject().put("id", 1).put("moodle_assignment_id", 7)))
        .put("assignment_overrides", JSONArray().put(JSONObject().put("moodle_assignment_id", 7).put("local_status", "ignored")))
        .put(
            "courses",
            JSONArray().put(
                JSONObject().put("course_no", COURSE).put("course_name", "Old run's").put("semester", term).put("moodle_id", "$term$COURSE"),
            ),
        )
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("moodle_id", "$term$COURSE").put("course_no", COURSE).put("color_hex", "#123456")
                    .put("semester", term).put("ignore_assignments", true)
                    .put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
            ),
        )
        .put("courses_reset_at", "2026-10-13T00:00:00Z")
        .put("current_revision", 42)
        .toString()

    /** The background pass with its fetch held until [meanwhile] is done, answered with [body]. */
    private suspend fun pullAcross(body: String, status: Int = 200, meanwhile: suspend () -> Unit): SyncOutcome {
        val fetch = backend.hold(FULL_SYNC)
        val signIn = w.sessions.school.signIns.current
        val pulling = appScope.async { rig.synchronizer.sync(SyncMode.BACKGROUND, signIn) }
        withTimeout(10_000) { fetch.arrived.await() }
        meanwhile()
        fetch.body = body
        fetch.release(status)
        return withTimeout(10_000) { pulling.await() }
    }

    @Test
    fun `a same-student sign-in during the background pass writes nothing from the old pass`() = runBlocking {
        val term = w.courseService.currentSemesterCode()
        // The new session's term as it holds it, which the old pass's course list must leave.
        val kept = listOf(Course(courseNo = COURSE, courseName = "New session's", moodleIdNumber = "$term$COURSE"))

        pullAcross(snapshot(term)) {
            signOutAndBackIn()
            w.dataCache.saveCourses(kept, term, testSignIn())
        }

        val prefs = w.appPrefs
        val landed = mapOf(
            "marks synced once" to prefs.school.assignmentMarksSyncedOnce.get(),
            "course reset read" to prefs.school.lastSeenCoursesResetAt.get(),
            "first pull" to rig.gate.hasPulled(w.sessions.school.signIns.current),
            "marks" to w.dataCache.loadIgnoredAssignments(),
            "ignored courses" to w.dataCache.loadIgnoredCourses(),
            "server known" to w.dataCache.loadServerKnownNos(),
            "colors" to w.dataCache.loadCourses(term).mapNotNull { it.customColorHex },
        )
        val nothing = mapOf(
            "marks synced once" to false,
            "course reset read" to null,
            "first pull" to false,
            "marks" to emptySet<String>(),
            "ignored courses" to emptySet<String>(),
            "server known" to emptyMap<String, List<String>>(),
            "colors" to emptyList<String>(),
        )
        assertEquals(nothing, landed)
        assertEquals(listOf("New session's"), w.dataCache.loadCourses(term).map { it.courseName })
    }

    @Test
    fun `a 401 past a sign-out signs nobody in and fetches nothing again`() = runBlocking {
        pullAcross(body = "{}", status = 401) { signOutAndBackIn() }

        assertEquals("the one fetch, and no replay", 1, backend.received(FULL_SYNC).size)
        assertEquals(emptyList<String>(), backend.received(LOGIN).map { it.endpoint })
    }

    @Test
    fun `a pass that fails past its sign-out leaves where the data came from to the next sign-in`() = runBlocking {
        val outcome = pullAcross(body = "{}", status = 500) {
            signOutAndBackIn()
            // What the next sign-in's own sync said.
            w.appPrefs.setLastSyncSource(SyncSource.BACKEND)
        }

        assertEquals(SyncOutcome.SKIPPED, outcome)
        assertEquals(SyncSource.BACKEND, w.appPrefs.lastSyncSource.value)
        assertFalse(rig.gate.hasPulled(w.sessions.school.signIns.current))
    }

    private companion object {
        const val FULL_SYNC = "GET /sync/full"
        const val LOGIN = "POST /auth/login"
        const val STUDENT = "B00015999"
        const val COURSE = "CS1001"
    }
}
