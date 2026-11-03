package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.parseFullSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.reconcile.FirstSyncReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A `/v4` pull's course colors and custom names (LWW D12) where this phone holds more than the
 * outbox says: a hand-added course's color and name, which no device here can send (it has no
 * Moodle id); an older build's queued rename of a past term's course; a pick saved while the pull
 * runs; and a name the snapshot takes away, which Home and the widgets show from the cached
 * courses. Over the real stores and queues; made-up accounts and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LwwOverridesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var signIn: SignIn
    private lateinit var term: String
    private lateinit var policies: SyncPolicies
    private lateinit var overrides: PendingCourseOverrides
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
        overrides = PendingCourseOverrides(w.dataCache, w.pushApi, w.appPrefs, policies)
        outbox = Outbox(setOf(overrides))
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    private fun snapshot(vararg rows: JSONObject): BackendSyncResult = parseFullSync(
        JSONObject()
            .put("current_revision", 1)
            .put("server_hlc", 1_793_000_000_000_000L)
            .put("assignments", JSONArray())
            .put("courses", JSONArray())
            .put("course_overrides", JSONArray().apply { rows.forEach { put(it) } }),
        protocol2 = true,
    )

    private fun colorRow(no: String, hex: String) = JSONObject()
        .put("moodle_id", "$term$no").put("course_no", no).put("semester", term).put("course_key", SyncField.courseKey(term, no))
        .put("color_hex", hex).put("color_hex_hlc", 1_792_000_000_000_000L).put("custom_names", JSONObject())

    private fun pass(result: BackendSyncResult, syncedBefore: Set<SyncCategory> = SyncCategory.entries.toSet()) = SyncPass(
        result = result,
        signIn = signIn,
        mode = SyncMode.FOREGROUND,
        policy = policies.current,
        state = state,
        courseSync = DataCache.CourseSync(0L) { 0L },
        beforeFetch = 0L,
        syncedBefore = syncedBefore,
        heldFields = outbox::heldFields,
    )

    private fun reconciler() = OverridesReconciler(w.dataCache, w.widgetUpdater, overrides, policies, w.appPrefs)

    private val locale get() = AppLanguageManager.resolvedCourseApiLanguage(w.appPrefs.appLanguage)

    private suspend fun show(vararg courses: Course) {
        w.dataCache.saveCourses(courses.toList(), term, signIn)
        state.allCourses.value = courses.toList()
    }

    /**
     * Finding 1: a course added by hand here has no Moodle id, so neither the class table nor a
     * first sync can send its color or name, and no snapshot holds them. They are this phone's,
     * at the first sync of colors and names and after it alike.
     */
    @Test
    fun `a hand-added course's color and name, which nothing here can send, outlive a v4 snapshot without them`() = runBlocking {
        show(Course(courseNo = "ADD1", courseName = "Added", isManual = true, customColorHex = "#ABCDEF"))
        w.dataCache.saveCourseCustomNames(mapOf("ADD1" to mapOf(locale to "My Added")), signIn)
        val firstSync = FirstSyncReconciler(
            w.dataCache,
            UnsentMarks(w.dataCache, w.pushApi, w.appPrefs, policies),
            CourseIgnoreSync(w.dataCache, w.pushApi, w.appPrefs, AssignmentNotificationScheduler(context), policies),
            overrides,
            PendingCourseDeletes(w.dataCache, PendingCourseAdds(w.dataCache, w.pushApi), w.pushApi),
        )
        val first = pass(snapshot(), syncedBefore = SyncCategory.entries.toSet() - SyncCategory.COURSE_COLORS - SyncCategory.COURSE_NAMES)

        firstSync.reconcile(first)
        reconciler().reconcile(first)
        reconciler().reconcile(pass(snapshot()))

        assertEquals("#ABCDEF", w.dataCache.loadCourses(term).single().customColorHex)
        assertEquals(mapOf("ADD1" to mapOf(locale to "My Added")), w.dataCache.loadCourseCustomNames())
    }

    /**
     * Finding 3: an older build's rename queued with no term names its course by its Moodle id,
     * here a past term's course; the snapshot has no row of it. The rename waits, so it stays.
     */
    @Test
    fun `an older build's rename of a past term's course, queued by its Moodle id, stays while it waits`() = runBlocking {
        val past = "1101"
        w.dataCache.saveCourses(listOf(Course(courseNo = "OLD1", courseName = "Old", moodleIdNumber = "${past}OLD1")), past, signIn)
        show(Course(courseNo = "NOW1", courseName = "Now", moodleIdNumber = "${term}NOW1"))
        w.dataCache.saveCourseCustomNames(mapOf("OLD1" to mapOf(locale to "Renamed Old")), signIn)
        w.dataCache.savePendingCourseOverrides(
            listOf(PendingCourseOverride("${past}OLD1", colorHex = null, customName = "Renamed Old", locale = locale, changedAtMs = 1L, madeAtMs = 1L)),
            signIn,
        )

        reconciler().reconcile(pass(snapshot()))

        assertEquals(mapOf("OLD1" to mapOf(locale to "Renamed Old")), w.dataCache.loadCourseCustomNames())
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
