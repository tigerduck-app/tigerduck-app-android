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

    /**
     * Round 1, finding 2: a pick made while the pull waits on the names' lock, after it read the
     * courses, is not saved over by the pull's older copy of the list: the colors are decided from
     * the courses as the write that commits them reads them. The pick is the class table's
     * (`ClassTableViewModel.updateCourseColor`): its whole list built at the tap, saved, and its
     * change kept to go up, each started at once.
     *
     * Only the pick is asserted. That whole-list save, built before it waits behind the step, also
     * writes back course B as the tap found it, over the snapshot's color the step gave B: the
     * class table's write computed from a snapshot before an await, race 5, which is PR R's
     * (T3.30, `ClassTableViewModel`), not this step's.
     */
    @Test
    fun `a color picked while the pull waits on the names is not saved over by the pull's older list`() = runBlocking {
        show(
            Course(courseNo = "A1", courseName = "A", moodleIdNumber = "${term}A1", customColorHex = "#111111"),
            Course(courseNo = "B1", courseName = "B", moodleIdNumber = "${term}B1"),
        )
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            w.dataCache.updateCourseCustomNames(signIn) { names ->
                holding.complete(Unit)
                gate.await()
                names
            }
        }
        withTimeout(10_000) { holding.await() }
        val pull = launch(Dispatchers.Default) { reconciler().reconcile(pass(snapshot(colorRow("B1", "#222222")))) }
        delay(300)
        // The class table's pick, as it makes it: the list built at the tap, then saved whole and kept to go up.
        val picked = w.dataCache.loadCourses(term).map { if (it.courseNo == "A1") it.copy(customColorHex = "#333333") else it }
        val save = launch(Dispatchers.Default) { w.dataCache.saveCourses(picked, term, signIn) }
        val keep = launch(Dispatchers.Default) {
            overrides.queue(
                listOf(PendingCourseOverrides.Change("${term}A1", colorHex = "#333333", semester = term, courseNo = "A1")),
                overrides.reserveVersion(),
                signIn,
            )
        }
        delay(300)
        gate.complete(Unit)
        withTimeout(10_000) {
            holder.join()
            pull.join()
            save.join()
            keep.join()
        }

        assertEquals("#333333", w.dataCache.loadCourses(term).single { it.courseNo == "A1" }.customColorHex)
    }

    /**
     * Round 2, finding 2: an older build's rename queued by a Moodle id two terms' courses share
     * holds the name of each, as it may be either's: holding one too many is safe, and losing a
     * waiting rename is not.
     */
    @Test
    fun `an older build's rename by a Moodle id two terms' courses share holds the name of each`() = runBlocking {
        val past = "1101"
        w.dataCache.saveCourses(listOf(Course(courseNo = "SHA", courseName = "A", moodleIdNumber = "SHARED1")), past, signIn)
        show(Course(courseNo = "SHB", courseName = "B", moodleIdNumber = "SHARED1"))
        val names = mapOf("SHA" to mapOf(locale to "Renamed A"), "SHB" to mapOf(locale to "Renamed B"))
        w.dataCache.saveCourseCustomNames(names, signIn)
        w.dataCache.savePendingCourseOverrides(
            listOf(PendingCourseOverride("SHARED1", colorHex = null, customName = "Renamed A", locale = locale, changedAtMs = 1L, madeAtMs = 1L)),
            signIn,
        )

        reconciler().reconcile(pass(snapshot()))

        assertEquals(names, w.dataCache.loadCourseCustomNames())
    }

    /**
     * Round 2, finding 3: a course added by hand, and its name, saved after the pull began and
     * before its step takes the courses' lock, are read by that step: which course numbers no
     * course here can send a name for is decided in the step that writes.
     */
    @Test
    fun `a hand-added course and its name saved just before the step are read by it, and the name stays`() = runBlocking {
        show(Course(courseNo = "C1", courseName = "C", moodleIdNumber = "${term}C1"))
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        // A save of the term under way as the pull starts: the hand-add, which lands once let go.
        val add = launch(Dispatchers.Default) {
            w.dataCache.updateCourses(term, signIn) { courses, _ ->
                holding.complete(Unit)
                gate.await()
                courses + Course(courseNo = "ADD2", courseName = "Added", isManual = true)
            }
        }
        withTimeout(10_000) { holding.await() }
        val pull = launch(Dispatchers.Default) { reconciler().reconcile(pass(snapshot())) }
        delay(300)
        w.dataCache.updateCourseCustomNames(signIn) { it + ("ADD2" to mapOf(locale to "My Added")) }
        gate.complete(Unit)
        withTimeout(10_000) {
            add.join()
            pull.join()
        }

        assertEquals(mapOf("ADD2" to mapOf(locale to "My Added")), w.dataCache.loadCourseCustomNames())
        assertEquals("My Added", w.dataCache.loadCourses(term).single { it.courseNo == "ADD2" }.customCourseName)
    }

    /**
     * Finding 4: a name the snapshot no longer holds leaves the names and the courses Home and the
     * widgets show it from, the cached term's and Home's.
     */
    @Test
    fun `a name the snapshot takes away leaves the cached courses and Home's too`() = runBlocking {
        show(Course(courseNo = "N1", courseName = "School Name", moodleIdNumber = "${term}N1", customCourseName = "Gone Name"))
        w.dataCache.saveCourseCustomNames(mapOf("N1" to mapOf(locale to "Gone Name")), signIn)

        reconciler().reconcile(pass(snapshot()))

        assertEquals(emptyMap<String, Map<String, String>>(), w.dataCache.loadCourseCustomNames())
        assertEquals(listOf<String?>(null), w.dataCache.loadCourses(term).map { it.customCourseName })
        assertEquals(listOf<String?>(null), state.allCourses.value.map { it.customCourseName })
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
