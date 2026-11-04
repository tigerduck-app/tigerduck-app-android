package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.ntust.app.tigerduck.push.WriteAnswer

/**
 * The same student signs out and back in while Home's full sync has its fetch out. The student ID
 * matches throughout, so only the sign-in tells the two sessions apart: nothing of the old run
 * lands, on disk, in the preferences or in what Home shows, and the new session's data stays as
 * it is. Runs the real [HomeBackendSync], [org.ntust.app.tigerduck.auth.AuthService.logout] and
 * sign-in over the real stores, with the backend scripted. Made-up accounts and course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomePullAcrossSignInTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend(under = "/v4")
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var marks: UnsentMarks
    private lateinit var adds: PendingCourseAdds
    private lateinit var ignores: CourseIgnoreSync
    private lateinit var sync: HomeBackendSync

    /** What follows a write of the ignored courses: a test stands a sign-out there. */
    private var ignoresChanged: suspend () -> Unit = {}

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(listOf(Course(courseNo = COURSE, courseName = "Shown"))),
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
        val ignores = CourseIgnoreSync(
            w.dataCache,
            writer = { _, ignore, _, _, _ -> WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to ignore)) },
            effects = { ignoresChanged() },
            sends = { CourseIgnoreSync.syncsCourseIgnores(w.appPrefs) },
            clock = System::currentTimeMillis,
        )
        val rig = homeSyncRig(context, w, backend.client, idleScope, ignores)
        marks = rig.marks
        adds = rig.adds
        this.ignores = rig.ignores
        sync = rig.sync
        signIn()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** The student's school sign-in and backend session, as a sign-in leaves them. */
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

    /** The account's full sync as the backend would answer it: something for every step to take. */
    private fun snapshot() = JSONObject()
        .put("assignments", JSONArray().put(JSONObject().put("id", 1).put("moodle_assignment_id", 7)))
        .put("assignment_overrides", JSONArray().put(JSONObject().put("moodle_assignment_id", 7).put("local_status", "ignored")))
        .put(
            "courses",
            JSONArray().put(
                JSONObject().put("course_no", COURSE).put("course_name", "Old run's").put("semester", TERM)
                    .put("moodle_id", MOODLE_ID),
            ).put(JSONObject().put("course_no", ADDED).put("course_name", "Old run's").put("semester", TERM)),
        )
        .put(
            "course_overrides",
            JSONArray().put(
                JSONObject().put("moodle_id", MOODLE_ID).put("course_no", COURSE).put("color_hex", "#123456")
                    .put("semester", TERM).put("ignore_assignments", true)
                    .put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
            ).put(
                JSONObject().put("moodle_id", "$TERM$ADDED").put("course_no", ADDED).put("semester", TERM)
                    .put("ignore_assignments", true).put("ignore_assignments_updated_at", "2026-10-13T00:00:00Z"),
            ),
        )
        .put("courses_reset_at", "2026-10-13T00:00:00Z")
        .put("current_revision", 42)
        .toString()

    /** Runs a full sync with its fetch held until [meanwhile] is done, answered with [body]. */
    private suspend fun pullAcross(body: String = snapshot(), status: Int = 200, meanwhile: suspend () -> Unit) {
        val fetch = backend.hold(FULL_SYNC)
        val pulling = appScope.async { sync.pull(state, w.sessions.school.signIns.current) }
        withTimeout(10_000) { fetch.arrived.await() }
        meanwhile()
        fetch.body = body
        fetch.release(status)
        withTimeout(10_000) { pulling.await() }
    }

    @Test
    fun `a same-student sign-in during Home's pull writes nothing from the old run`() = runBlocking {
        pullAcross { signOutAndBackIn() }

        val prefs = w.appPrefs
        val landed = mapOf(
            "marks synced once" to prefs.school.assignmentMarksSyncedOnce.get(),
            "categories synced" to prefs.school.syncedCategories.get(),
            "course reset read" to prefs.school.lastSeenCoursesResetAt.get(),
            "watermark" to state.lastKnownRevision.value,
            "marks shown" to state.ignoredAssignmentIds.value,
            "ignores shown" to state.ignoredCourseKeys.value,
            "colors shown" to state.allCourses.value.mapNotNull { it.customColorHex },
        )
        val nothing = mapOf(
            "marks synced once" to false,
            "categories synced" to emptySet<String>(),
            "course reset read" to null,
            "watermark" to 0L,
            "marks shown" to emptySet<String>(),
            "ignores shown" to emptySet<String>(),
            "colors shown" to emptyList<String>(),
        )
        assertEquals(nothing, landed)
        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredAssignments())
        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredCourses())
        assertEquals(emptyMap<String, List<String>>(), w.dataCache.loadServerKnownNos())
    }

    /** Past the write: what the file took, a sign-out took with it, and Home shows none of it. */
    @Test
    fun `a sign-out as the ignores land leaves what Home shows alone`() = runBlocking {
        ignoresChanged = {
            ignoresChanged = {}
            signOutAndBackIn()
        }

        pullAcross {}

        assertEquals(emptySet<String>(), state.ignoredCourseKeys.value)
    }

    @Test
    fun `the new session's first write survives an old run ending`() = runBlocking {
        pullAcross {
            signOutAndBackIn()
            // The new session's first edits, made while the old run's fetch is still out.
            val course = Course(courseNo = COURSE, courseName = "New session's", isManual = true)
            val added = Course(courseNo = ADDED, courseName = "New session's", isManual = true)
            w.dataCache.saveCourses(listOf(course, added), TERM, testSignIn())
            val signIn = w.sessions.school.signIns.current
            marks.keep("8", "locally_completed", signIn)
            ignores.setIgnored(TERM, COURSE, MOODLE_ID, null, ignore = true, signIn = signIn)
            adds.send(listOf(added), TERM, ADDED, addedAt = 1L, signIn = signIn)
        }

        assertEquals(setOf(COURSE, ADDED), w.dataCache.loadCourses(TERM).map { it.courseNo }.toSet())
        assertEquals(setOf("New session's"), w.dataCache.loadCourses(TERM).map { it.courseName }.toSet())
        assertEquals(mapOf("8" to "locally_completed"), marks.statuses())
        assertEquals(setOf("$TERM:$COURSE"), w.dataCache.loadIgnoredCourses())
        assertEquals("the old snapshot lists it, and settles nothing", mapOf(TERM to listOf(ADDED)), w.dataCache.loadPendingCourseAdds())
    }

    @Test
    fun `a 401 past a sign-out signs nobody in and replays nothing`() = runBlocking {
        pullAcross(body = "{}", status = 401) { signOutAndBackIn() }

        assertEquals("the one fetch, and no replay", 1, backend.received(FULL_SYNC).size)
        assertEquals(emptyList<String>(), backend.received(LOGIN).map { it.endpoint })
    }

    /** The next account's status bar and banner: the old run has nothing to say of its backend. */
    @Test
    fun `a run past its sign-out leaves the backend's status to the next account`() = runBlocking {
        pullAcross(body = "{}", status = 500) {
            signOutAndBackIn()
            // What the next account's own sync reported.
            ServerStatusTracker.set(ServerStatus.OK, ServerKind.BACKEND)
            w.appPrefs.setLastSyncSource(SyncSource.BACKEND)
        }

        assertEquals(ServerStatus.OK, ServerStatusTracker.statuses.value[ServerKind.BACKEND])
        assertEquals(SyncSource.BACKEND, w.appPrefs.lastSyncSource.value)
    }

    /** Read before the fetch: read after, a sign-out meanwhile would pin these marks to the next session. */
    @Test
    fun `a first-sync mark upload is pinned to the sign-in the run began under`() = runBlocking {
        pullAcross(body = """{"assignments":[],"current_revision":42}""") {
            signOutAndBackIn()
            // This phone's marks with an empty server: the old run takes them for a first sync.
            w.dataCache.saveIgnoredAssignments(setOf("7"), testSignIn())
            state.ignoredAssignmentIds.value = setOf("7")
        }

        assertEquals(emptyList<String>(), backend.received(MARK).map { it.endpoint })
    }

    private companion object {
        const val FULL_SYNC = "GET /sync/full"
        const val LOGIN = "POST /auth/login"
        const val MARK = "PATCH /sync/assignments/7/override"
        const val STUDENT = "B00015999"
        const val TERM = "1141"
        const val COURSE = "CS1001"
        const val ADDED = "AD1001"
        const val MOODLE_ID = "1141CS1001"
    }
}
