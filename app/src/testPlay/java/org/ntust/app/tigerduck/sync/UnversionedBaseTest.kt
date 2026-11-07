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
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A backend at a base that names no API version, as a self-hosted setting may (`https://host/api`),
 * is spoken to as every other: `/v4`'s requests, sent to the base as given, and `/v4`'s rules for
 * what this phone keeps and applies. Over the app's own wiring ([appSyncRig]). Made-up account and
 * course numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UnversionedBaseTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: AppSyncRig
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
        val prefs = AppPreferences(context)
        prefs.announcementApiBaseUrlOverride = UNVERSIONED
        // Never the real backend, even past the scripted one.
        assumeTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val clock = DeviceClocks.WALL
        val client = appBackendClient(prefs, backend.client, V4WriteRules())
        val credentialPrefs = context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE)
        w = accountWorld(context, credentialPrefs, appScope, idleScope, backend = client)
        rig = appSyncRig(context, w, client, clock, appScope, idleScope, oldEntryTimeAt(context, FIRST_RUN_MS))
        runBlocking {
            assertTrue(w.auth.login(STUDENT, "made-up-password"))
            w.credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)
        }
        signIn = w.sessions.school.signIns.current
        term = w.dataCache.currentSemesterCode()
        // Every category synced before, so no first sync of one joins the requests.
        w.appPrefs.school.syncedCategories.set(SyncCategory.entries.map { it.stored }.toSet(), signIn)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun snapshot() = JSONObject()
        .put("current_revision", 3)
        .put("server_hlc", 1_793_000_000_000_000L)
        .put("assignments", JSONArray())
        .put("assignment_overrides", JSONArray())
        .put("course_overrides", JSONArray())
        .put("term_resets", JSONObject())
        .put(
            "courses",
            JSONArray().put(
                JSONObject().put("semester", term).put("course_key", "client:$term:$HIDDEN").put("course_no", HIDDEN)
                    .put("course_name", "School $HIDDEN").put("moodle_id", "$term$HIDDEN").put("source", "roster")
                    .put("enrollment_status", "enrolled"),
            ),
        )
        .put("course_tombstones", JSONArray())
        .toString()

    /**
     * A course hidden while course sync is off is kept to go up once it is on (LWW D12), so the
     * pull after holds the hide while its delete waits: `/v4`'s rule. With `/v3`'s, the delete was
     * let go, and the pull, which reads the snapshot as `/v4` serves it, lifted the hide.
     */
    @Test
    fun `a course hidden with course sync off stays hidden through the pull after, its delete waiting`() = runBlocking {
        w.dataCache.saveCourses(listOf(Course(courseNo = HIDDEN, courseName = "School $HIDDEN", moodleIdNumber = "$term$HIDDEN")), term, signIn)
        w.appPrefs.syncCourses = false
        // As the class table hides a course: the hide here, then its delete.
        w.dataCache.saveDeletedCourseNos(setOf("$term:$HIDDEN"), signIn)
        rig.home.deletes.send(term, HIDDEN, signIn)
        w.appPrefs.syncCourses = true
        // The backend is busy for the delete, which still waits as the pull reads the term.
        listOf("client:$term:$HIDDEN", "client%3A$term%3A$HIDDEN").forEach { backend.answer("DELETE /sync/courses/$it", 503, 503, 503) }
        backend.answer("GET /sync/full", 200, snapshot())

        rig.home.sync.pull(state, signIn)

        assertEquals(setOf("$term:$HIDDEN"), w.dataCache.loadDeletedCourseNos())
        assertEquals("the delete still waits", setOf(HIDDEN), rig.home.deletes.waitingIn(term))
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val UNVERSIONED = "http://127.0.0.1:9"
        const val HIDDEN = "XH0000001"
        const val FIRST_RUN_MS = 1_760_000_000_000L
    }
}
