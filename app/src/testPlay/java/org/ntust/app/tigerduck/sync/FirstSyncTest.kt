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
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.parseFullSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.reconcile.FirstSyncReconciler
import org.ntust.app.tigerduck.sync.reconcile.IgnoresReconciler
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.sync.reconcile.RosterReconciler
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.idleTermSync
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A category's first sync on `/v4` (LWW D13, `tigersync-merge`, "Existing device data counts as
 * oldest"): never synced here, each local value is queued stamped 0 and kept over a snapshot that
 * lacks it, so it fills only a field the backend holds no stamp for; synced before, nothing goes
 * and the snapshot replaces it. Both cases for each category: the marks and the ignored courses,
 * the colors, the names and the course list's hides (X8). Over the real stores and queues; made-up
 * accounts and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FirstSyncTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var signIn: SignIn
    private lateinit var term: String
    private lateinit var policies: SyncPolicies
    private lateinit var marks: UnsentMarks
    private lateinit var ignores: CourseIgnoreSync
    private lateinit var overrides: PendingCourseOverrides
    private lateinit var adds: PendingCourseAdds
    private lateinit var deletes: PendingCourseDeletes
    private lateinit var outbox: Outbox

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
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.dataCache.currentSemesterCode()
        policies = SyncPolicies(w.appPrefs)
        marks = UnsentMarks(w.dataCache, w.pushApi, w.appPrefs, policies)
        ignores = CourseIgnoreSync(w.dataCache, w.pushApi, w.appPrefs, AssignmentNotificationScheduler(context), policies)
        overrides = PendingCourseOverrides(w.dataCache, w.pushApi, w.appPrefs, policies)
        adds = PendingCourseAdds(w.dataCache, w.pushApi)
        deletes = PendingCourseDeletes(w.dataCache, adds, w.pushApi)
        outbox = Outbox(setOf(ignores.channel, marks, adds, deletes, overrides))
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** A `/v4` snapshot that holds nothing of this phone's, only another course's row of its term. */
    private fun emptySnapshot(): BackendSyncResult = parseFullSync(
        JSONObject()
            .put("current_revision", 1)
            .put("server_hlc", 1_793_000_000_000_000L)
            .put("assignments", JSONArray())
            .put("assignment_overrides", JSONArray())
            .put("course_overrides", JSONArray())
            .put("course_tombstones", JSONArray())
            .put(
                "courses",
                JSONArray().put(
                    JSONObject().put("id", 9).put("semester", term).put("course_key", "client:$term:ZZ9").put("course_no", "ZZ9")
                        .put("course_name", "Another").put("moodle_id", "${term}ZZ9").put("source", "roster"),
                ),
            ),
    )

    private fun pass(result: BackendSyncResult, syncedBefore: Set<SyncCategory>, mode: SyncMode = SyncMode.FOREGROUND) = SyncPass(
        result = result,
        signIn = signIn,
        mode = mode,
        policy = policies.current,
        state = state,
        courseSync = DataCache.CourseSync(0L) { 0L },
        beforeFetch = 0L,
        syncedBefore = syncedBefore,
    )

    private fun steps() = listOf(
        FirstSyncReconciler(w.dataCache, marks, ignores, overrides, deletes),
        MarksReconciler(w.appPrefs, w.dataCache),
        IgnoresReconciler(ignores),
        OverridesReconciler(w.dataCache, w.widgetUpdater, overrides, policies, w.appPrefs),
        RosterReconciler(
            w.dataCache, w.auth, w.pushApi, w.courseService, w.semesterCatalog, w.widgetUpdater, adds, deletes, overrides,
            idleTermSync(w), idleScope, policies,
        ),
    )

    /** This phone's values from before the update: a mark of each kind, an ignored course, a color, a name and a hide. */
    private fun seed() = runBlocking {
        w.dataCache.saveIgnoredAssignments(setOf("7"), signIn)
        w.dataCache.saveMarkedCompletedAssignments(setOf("8"), signIn)
        state.ignoredAssignmentIds.value = setOf("7")
        state.markedCompletedIds.value = setOf("8")
        w.dataCache.saveIgnoredCourses(setOf("$term:XA1"), signIn)
        val courses = listOf(Course(courseNo = "XA1", courseName = "Course One", moodleIdNumber = "${term}XA1", customColorHex = "#123456"))
        w.dataCache.saveCourses(courses, term, signIn)
        state.allCourses.value = courses
        w.dataCache.saveCourseCustomNames(mapOf("XA1" to mapOf("en" to "Mine")), signIn)
        w.dataCache.saveDeletedCourseNos(setOf("$term:XA2"), signIn)
    }

    private fun run(pass: SyncPass) = runBlocking { for (step in steps()) step.reconcile(pass) }

    @Test
    fun `a category never synced queues each local value as the oldest, and the pull keeps them`() = runBlocking {
        seed()

        run(pass(emptySnapshot(), syncedBefore = emptySet()))

        assertEquals(setOf("7"), w.dataCache.loadIgnoredAssignments())
        assertEquals(setOf("8"), w.dataCache.loadMarkedCompletedAssignments())
        assertEquals(mapOf("7" to 0L, "8" to 0L), w.dataCache.loadUnsentMarks().associate { it.assignmentId to it.madeAtMs })
        assertEquals(setOf("$term:XA1"), w.dataCache.loadIgnoredCourses())
        assertEquals(listOf("$term:XA1" to 0L), w.dataCache.loadPendingCourseIgnores().map { it.key to it.madeAtMs })
        assertEquals("#123456", w.dataCache.loadCourses(term).single { it.courseNo == "XA1" }.customColorHex)
        assertEquals(mapOf("XA1" to mapOf("en" to "Mine")), w.dataCache.loadCourseCustomNames())
        assertEquals(
            setOf(Triple("#123456", null, 0L), Triple(null, "Mine", 0L)),
            w.dataCache.loadPendingCourseOverrides().mapTo(HashSet()) { Triple(it.colorHex, it.customName, it.madeAtMs) },
        )
        // X8: a hide only on this phone, never sent, goes up as its own delete, stamped 0, and stays.
        assertEquals(setOf("$term:XA2"), w.dataCache.loadDeletedCourseNos())
        assertEquals(setOf("XA2"), deletes.waitingIn(term))
    }

    /** "Upgraded device that synced marks before": a value the server lacks is dropped, and none goes up. */
    @Test
    fun `a category synced before queues nothing, and the snapshot replaces its values`() = runBlocking {
        seed()

        run(pass(emptySnapshot(), syncedBefore = SyncCategory.entries.toSet()))

        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredAssignments())
        assertEquals(emptySet<String>(), w.dataCache.loadMarkedCompletedAssignments())
        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredCourses())
        assertEquals(null, w.dataCache.loadCourses(term).single { it.courseNo == "XA1" }.customColorHex)
        assertEquals(emptyMap<String, Map<String, String>>(), w.dataCache.loadCourseCustomNames())
        assertEquals(emptySet<String>(), w.dataCache.loadDeletedCourseNos())
        assertTrue(w.dataCache.loadUnsentMarks().isEmpty() && w.dataCache.loadPendingCourseIgnores().isEmpty())
        assertTrue(w.dataCache.loadPendingCourseOverrides().isEmpty() && deletes.waitingIn(term).isEmpty())
    }

    /**
     * "First sync of a category": the queued color goes as the oldest, `hlc: 0` with no age, which
     * fills only a course the backend holds no color for and changes no other.
     */
    @Test
    fun `a first color sync sends its colors as the oldest`() = runBlocking {
        seed()
        run(pass(emptySnapshot(), syncedBefore = SyncCategory.entries.toSet() - SyncCategory.COURSE_COLORS))

        overrides.drain(signIn)

        val sent = backend.received("PATCH /v4/sync/courses/client:$term:XA1/override").map { JSONObject(it.body) }
        assertEquals(listOf("#123456" to 0L), sent.map { it.getString("color_hex") to it.getLong("hlc") })
        assertTrue(sent.none { it.has("age_ms") })
    }

    /** In the background (C2), a first sync queues nothing: it is Home's. */
    @Test
    fun `the first sync queues nothing in the background`() = runBlocking {
        seed()
        val firstSync = FirstSyncReconciler(w.dataCache, marks, ignores, overrides, deletes)

        firstSync.reconcile(pass(emptySnapshot(), syncedBefore = emptySet(), mode = SyncMode.BACKGROUND))

        assertTrue(w.dataCache.loadUnsentMarks().isEmpty() && w.dataCache.loadPendingCourseIgnores().isEmpty())
        assertTrue(w.dataCache.loadPendingCourseOverrides().isEmpty() && deletes.waitingIn(term).isEmpty())
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
