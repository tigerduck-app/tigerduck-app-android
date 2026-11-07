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
import org.ntust.app.tigerduck.data.moment
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home's `/v4` pull over the real stores, the course list's membership from the snapshot's
 * tombstones (LWW D10): a hidden course out and hidden, a hand-add a
 * removal took out with no hide, another device's row down, a hide the backend lacks lifted, a
 * school course it does not mention kept, and a course whose delete waits kept hidden. Made-up
 * accounts and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MembershipPullTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig
    private lateinit var signIn: SignIn
    private lateinit var term: String

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
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = backend.client)
        w.appPrefs.announcementApiBaseUrlOverride = "http://127.0.0.1:9/v4"
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(w.appPrefs).overrideApplied)
        rig = homeSyncRig(context, w, backend.client, idleScope)
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.dataCache.currentSemesterCode()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun row(no: String, source: String = "roster", addedHlc: Long? = null) = JSONObject()
        .put("id", no.hashCode().toLong() and 0xFFFF).put("semester", term).put("course_key", "client:$term:$no").put("course_no", no)
        .put("course_name", "Row $no").put("moodle_id", "$term$no").put("source", source).put("enrollment_status", "enrolled")
        .put("added_hlc", addedHlc ?: JSONObject.NULL)

    private fun tombstone(no: String, kind: String, hlc: Long) = JSONObject()
        .put("course_key", "client:$term:$no").put("course_no", no).put("semester", term).put("deleted_at", "2026-10-20T02:00:00+00:00")
        .put("hlc", hlc).put("kind", kind)

    @Test
    fun `Home's v4 pull commits the term's membership from the snapshot's tombstones`() = runBlocking {
        val local = listOf(
            Course(courseNo = "A", courseName = "School A", moodleIdNumber = "${term}A"),
            Course(courseNo = "B", courseName = "Added B", isManual = true),
            Course(courseNo = "C", courseName = "School C", moodleIdNumber = "${term}C"),
            Course(courseNo = "F", courseName = "School F", moodleIdNumber = "${term}F"),
        )
        w.dataCache.saveCourses(local, term, signIn)
        w.dataCache.saveDeletedCourseNos(setOf("$term:D", "$term:F"), signIn)
        // F's delete waits: refused for now, so it stays this phone's.
        assertTrue(rig.deletes.keep(term, "F", moment(1L), signIn))
        listOf("client:$term:F", "client%3A$term%3AF").forEach { backend.answer("DELETE /v4/sync/courses/$it", 503, 503, 503) }
        val snapshot = JSONObject()
            .put("current_revision", 3)
            .put("server_hlc", 1_793_000_000_000_000L)
            .put("assignments", JSONArray())
            .put("assignment_overrides", JSONArray())
            .put("course_overrides", JSONArray())
            .put("term_resets", JSONObject())
            .put("courses", JSONArray().put(row("A")).put(row("C")).put(row("D")).put(row("E", "user_added", 50)).put(row("F")))
            .put("course_tombstones", JSONArray().put(tombstone("A", "hide", 40)).put(tombstone("B", "remove", 40)))
        backend.answer("GET /v4/sync/full", 200, snapshot.toString())

        rig.sync.pull(state, signIn)

        assertEquals(setOf("$term:A", "$term:F"), w.dataCache.loadDeletedCourseNos())
        assertEquals(listOf("C", "D", "E"), w.dataCache.loadCourses(term).map { it.courseNo }.sorted())
        assertEquals("the delete still waits", setOf("F"), rig.deletes.waitingIn(term))
    }

    /**
     * A course reset elsewhere reaches this phone as its tombstones: no wipe of every hand-added
     * course runs, whatever `courses_reset_at` an older build remembered.
     */
    @Test
    fun `a course reset read on a pull wipes no hand-added course its tombstones do not take`() = runBlocking {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("tigerduck_prefs", 0).edit().putString("lastSeenCoursesResetAt", "2026-09-01T00:00:00+00:00").commit()
        w.dataCache.saveCourses(listOf(Course(courseNo = "B", courseName = "Added B", isManual = true)), term, signIn)
        val snapshot = JSONObject()
            .put("current_revision", 3)
            .put("server_hlc", 1_793_000_000_000_000L)
            .put("courses_reset_at", "2026-10-30T00:00:00+00:00")
            .put("courses_reset_hlc", 1_792_000_000_000_000L)
            .put("assignments", JSONArray())
            .put("courses", JSONArray().put(row("C")))
            .put("course_tombstones", JSONArray())
        backend.answer("GET /v4/sync/full", 200, snapshot.toString())

        rig.sync.pull(state, signIn)

        assertEquals(listOf("B", "C"), w.dataCache.loadCourses(term).map { it.courseNo }.sorted())
    }

    /**
     * A delete kept after the pull read the outbox (here, a read that found none) is read again
     * at the term's own step: its hide stays, though the snapshot
     * lists the course.
     */
    @Test
    fun `a delete waiting as the term's step runs keeps its hide, though the pull read the outbox before it`() = runBlocking {
        w.dataCache.saveCourses(listOf(Course(courseNo = "C", courseName = "School C", moodleIdNumber = "${term}C")), term, signIn)
        w.dataCache.saveDeletedCourseNos(setOf("$term:F"), signIn)
        assertTrue(rig.deletes.keep(term, "F", moment(1L), signIn))
        val result = org.ntust.app.tigerduck.push.parseFullSync(
            JSONObject().put("current_revision", 3).put("server_hlc", 1_793_000_000_000_000L).put("assignments", JSONArray())
                .put("courses", JSONArray().put(row("C")).put(row("F"))).put("course_tombstones", JSONArray()),
        )
        val policies = SyncPolicies(w.appPrefs)
        val roster = org.ntust.app.tigerduck.sync.reconcile.RosterReconciler(
            w.dataCache, w.auth, w.pushApi, w.courseService, w.semesterCatalog, w.widgetUpdater, rig.adds, rig.deletes, rig.overrides,
            org.ntust.app.tigerduck.ui.screen.home.idleTermSync(w), idleScope, policies,
        )
        val pass = SyncPass(
            result, signIn, SyncMode.FOREGROUND, policies.current, state, org.ntust.app.tigerduck.data.cache.DataCache.CourseSync(0L) { 0L }, 0L,
            syncedBefore = SyncCategory.entries.toSet(),
        )

        roster.reconcile(pass)

        assertEquals(setOf("$term:F"), w.dataCache.loadDeletedCourseNos())
        assertEquals(listOf("C"), w.dataCache.loadCourses(term).map { it.courseNo })
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
