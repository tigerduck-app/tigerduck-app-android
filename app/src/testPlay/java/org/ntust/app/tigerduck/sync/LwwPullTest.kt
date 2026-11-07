package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.parseFullSync
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.reconcile.HolidaysReconciler
import org.ntust.app.tigerduck.sync.reconcile.IgnoresReconciler
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A `/v4` pull, field by field (LWW D12, `tigersync-merge`, "A client keeps unsent edits over the
 * snapshot"): a field the outbox holds an edit of keeps this phone's value, and so does one whose
 * last acknowledged write answered at a clock not earlier than the snapshot's `server_hlc`; every
 * other field takes the snapshot's, its absence included, and no conflict is offered. Over the real
 * stores and queues, each reconciler run on a pass of the `pull-fields.json` fixture, in Home's
 * mode and the background's. Made-up accounts and courses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LwwPullTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend()
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var signIn: SignIn
    private lateinit var term: String
    private val ack = AckClock()

    private lateinit var marks: UnsentMarks
    private lateinit var ignores: CourseIgnoreSync
    private lateinit var overrides: PendingCourseOverrides
    private lateinit var holidays: PendingHolidayOverrides
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
        val policies = SyncPolicies(w.appPrefs)
        marks = UnsentMarks(w.dataCache, w.pushApi, w.appPrefs, policies, ack = ack)
        ignores = CourseIgnoreSync(w.dataCache, w.pushApi, w.appPrefs, AssignmentNotificationScheduler(context), policies, ack = ack)
        overrides = PendingCourseOverrides(w.dataCache, w.pushApi, w.appPrefs, policies, ack = ack)
        holidays = PendingHolidayOverrides(w.appPrefs, w.pushApi, policies, ack = ack)
        outbox = Outbox(setOf(ignores.channel, marks, overrides, holidays))
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** The fixture, its term made this phone's current one, with [edit] made to it. */
    private fun snapshot(edit: (JSONObject) -> Unit = {}): BackendSyncResult {
        val raw = javaClass.classLoader!!.getResource("sync/v4/pull-fields.json").readText().replace("1151", term)
        return parseFullSync(JSONObject(raw).also(edit))
    }

    private fun pass(
        result: BackendSyncResult,
        mode: SyncMode,
        syncedBefore: Set<SyncCategory> = SyncCategory.entries.toSet(),
    ) = SyncPass(
        result = result,
        signIn = signIn,
        mode = mode,
        policy = SyncPolicies(w.appPrefs).current,
        state = state.takeIf { mode == SyncMode.FOREGROUND },
        courseSync = DataCache.CourseSync(0L) { 0L },
        beforeFetch = 0L,
        syncedBefore = syncedBefore,
        ack = ack,
    )

    private fun course(no: String, color: String?) = Course(courseNo = no, courseName = "Course $no", moodleIdNumber = "$term$no", customColorHex = color)

    private fun key(no: String) = SyncField.courseKey(term, no)

    private val serverHlc = 1_793_000_000_000_000L

    /**
     * This phone before the pull: marks, ignored courses, colors, names and holidays, some with an
     * edit waiting in the outbox, some acknowledged at or after the snapshot's `server_hlc`, one
     * just before it.
     */
    private fun seed() = runBlocking {
        w.dataCache.saveIgnoredAssignments(setOf("9", "10"), signIn)
        w.dataCache.saveMarkedCompletedAssignments(setOf("7"), signIn)
        state.ignoredAssignmentIds.value = setOf("9", "10")
        state.markedCompletedIds.value = setOf("7")
        marks.keep("7", "locally_completed", signIn)
        ack.acknowledged(SyncField.mark("11"), serverHlc + 5, signIn)
        ack.acknowledged(SyncField.mark("8"), serverHlc - 1, signIn)

        w.dataCache.saveIgnoredCourses(setOf("$term:XA0000002", "$term:XA0000003", "$term:XA0000005"), signIn)
        assertTrue(ignores.queueAsOldest(term, "XA0000005", "${term}XA0000005", null, ignore = true, signIn = signIn))
        ack.acknowledged(SyncField.ignore(key("XA0000004")), serverHlc, signIn)

        val courses = listOf(
            course("XA0000001", "#111111"), course("XA0000002", null), course("XA0000003", "#333333"),
            course("XA0000004", "#444444"), course("XA0000005", "#555555"),
        )
        w.dataCache.saveCourses(courses, term, signIn)
        state.allCourses.value = courses
        w.dataCache.saveCourseCustomNames(
            mapOf(
                "XA0000001" to mapOf("zh" to "Local One"),
                "XA0000002" to mapOf("en" to "Local Two"),
                "XA0000003" to mapOf("en" to "Local Three"),
                "XA0000004" to mapOf("en" to "Held Four"),
            ),
            signIn,
        )
        overrides.queue(
            listOf(
                PendingCourseOverrides.Change("${term}XA0000004", colorHex = "#444444", semester = term, courseNo = "XA0000004"),
                PendingCourseOverrides.Change("${term}XA0000004", customName = "Held Four", locale = "en", semester = term, courseNo = "XA0000004"),
            ),
            overrides.reserveVersion(),
            signIn,
        )
        // An older build's change names its course by its Moodle id alone, and so does its acknowledgment.
        ack.acknowledged(SyncField.color("${term}XA0000005"), serverHlc + 1, signIn)

        w.academicCalendar.applySyncedOverrides(setOf(8, 9, 10))
        w.appPrefs.holidayOverridesAwaitingUpload = setOf(9)
    }

    private fun assertFieldByField(home: Boolean) = runBlocking {
        // Marks: 7 queued and 11 acknowledged since keep this phone's; 8 the snapshot's, its
        // acknowledgment earlier; 9 cleared as the snapshot's none, 10 as absent from it.
        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredAssignments())
        assertEquals(setOf("7", "8"), w.dataCache.loadMarkedCompletedAssignments())
        // Ignores: 1 the snapshot's; 2 its false; 3 absent; 4 acknowledged at the snapshot's clock; 5 queued.
        assertEquals(setOf("$term:XA0000001", "$term:XA0000005"), w.dataCache.loadIgnoredCourses())
        val colors = mapOf(
            "XA0000001" to "#FF0000", "XA0000002" to "#00FF00", "XA0000003" to null, "XA0000004" to "#444444", "XA0000005" to "#555555",
        )
        assertEquals(colors, w.dataCache.loadCourses(term).associate { it.courseNo to it.customColorHex })
        if (home) {
            assertEquals(colors, state.allCourses.value.associate { it.courseNo to it.customColorHex })
            assertEquals(emptySet<String>(), state.ignoredAssignmentIds.value)
            assertEquals(setOf("7", "8"), state.markedCompletedIds.value)
            assertEquals(setOf("$term:XA0000001", "$term:XA0000005"), state.ignoredCourseKeys.value)
            assertEquals("no conflict is offered", emptyList<Any>(), state.conflicts.value)
        }
        // Names: the snapshot's, a cleared or absent one gone, the queued one kept.
        assertEquals(
            mapOf("XA0000001" to mapOf("en" to "Server One"), "XA0000004" to mapOf("en" to "Held Four")),
            w.dataCache.loadCourseCustomNames(),
        )
        // Holidays: 7 on, 8 off, 10 absent and off, 9 waiting and kept.
        assertEquals(setOf(7, 9), w.academicCalendar.optedInHolidayIds)
    }

    private fun reconcilers() = listOf(
        MarksReconciler(w.appPrefs, w.dataCache),
        IgnoresReconciler(ignores),
        OverridesReconciler(w.dataCache, w.widgetUpdater, overrides, SyncPolicies(w.appPrefs), w.appPrefs),
        HolidaysReconciler(holidays, w.academicCalendar),
    )

    @Test
    fun `Home's v4 pull keeps the fields this phone holds and takes the rest, absences included`() = runBlocking {
        seed()
        val pass = pass(snapshot(), SyncMode.FOREGROUND)

        for (step in reconcilers()) step.reconcile(pass)

        assertFieldByField(home = true)
    }

    @Test
    fun `the background's v4 pull keeps the fields this phone holds and takes the rest, absences included`() {
        seed()
        val pass = pass(snapshot(), SyncMode.BACKGROUND)

        offMain { for (step in reconcilers()) step.reconcile(pass) }

        assertFieldByField(home = false)
    }

    /**
     * Each step decides in its own write, from its channel's edits waiting there: an edit kept
     * while the pull runs is kept all the same, and so is a holiday chosen and acknowledged after
     * the snapshot began, by the backend's clock against the snapshot's.
     */
    @Test
    fun `each step keeps the edits its channel holds as it writes, though the pull read the outbox before them`() = runBlocking {
        seed()
        ack.acknowledged(SyncField.holiday(10), serverHlc + 1, signIn)
        val pass = pass(snapshot(), SyncMode.FOREGROUND)

        for (step in reconcilers()) step.reconcile(pass)

        assertEquals("the waiting mark", setOf("7", "8"), w.dataCache.loadMarkedCompletedAssignments())
        assertTrue("the waiting ignore", "$term:XA0000005" in w.dataCache.loadIgnoredCourses())
        assertEquals("the waiting color", "#444444", w.dataCache.loadCourses(term).single { it.courseNo == "XA0000004" }.customColorHex)
        assertEquals("the waiting name", mapOf("en" to "Held Four"), w.dataCache.loadCourseCustomNames()["XA0000004"])
        assertEquals("the waiting choice and the one made since", setOf(7, 9, 10), w.academicCalendar.optedInHolidayIds)
    }

    /** A `/v4` pull that runs on past its sign-out writes nothing into the next sign-in's files. */
    @Test
    fun `a v4 pull past its sign-out writes nothing for the next sign-in`() = runBlocking {
        val pass = pass(snapshot(), SyncMode.FOREGROUND)
        w.plans.signOut(SignOutReason.SCHOOL)
        withTimeout(10_000) { w.sessions.school.awaitOpen() }
        assertTrue(w.auth.login(STUDENT, "made-up-password"))
        state.allCourses.value = listOf(course("XA0000001", null))

        for (step in reconcilers()) step.reconcile(pass)

        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredAssignments())
        assertEquals(emptySet<String>(), w.dataCache.loadMarkedCompletedAssignments())
        assertEquals(emptySet<String>(), w.dataCache.loadIgnoredCourses())
        assertEquals(emptyMap<String, Map<String, String>>(), w.dataCache.loadCourseCustomNames())
        assertEquals(emptyList<Course>(), w.dataCache.loadCourses(term))
        assertEquals(emptySet<Int>(), w.academicCalendar.optedInHolidayIds)
        assertTrue(w.dataCache.loadUnsentMarks().isEmpty())
    }

    /** A category never synced here is Home's first sync's (LWW D13, C2): the background's v4 pull leaves it. */
    @Test
    fun `the background's v4 pull leaves the categories never synced here`() {
        seed()
        val pass = pass(snapshot(), SyncMode.BACKGROUND, syncedBefore = emptySet())

        offMain { for (step in reconcilers().take(3)) step.reconcile(pass) }

        runBlocking {
            assertEquals(setOf("9", "10"), w.dataCache.loadIgnoredAssignments())
            assertEquals(setOf("7"), w.dataCache.loadMarkedCompletedAssignments())
            assertEquals(setOf("$term:XA0000002", "$term:XA0000003", "$term:XA0000005"), w.dataCache.loadIgnoredCourses())
            assertEquals("#333333", w.dataCache.loadCourses(term).single { it.courseNo == "XA0000003" }.customColorHex)
            assertEquals(mapOf("en" to "Local Three"), w.dataCache.loadCourseCustomNames()["XA0000003"])
        }
    }

    /**
     * Review Focus 4: a snapshot fetched before a color write landed, applied after the write's
     * reply, keeps the acknowledged color; a later one without the color, the course hidden
     * elsewhere since, drops it.
     */
    @Test
    fun `an acknowledged color outlives an older snapshot, and goes with a later one that lacks it`() = runBlocking {
        val courses = listOf(course("XA0000001", "#ABCDEF"))
        w.dataCache.saveCourses(courses, term, signIn)
        state.allCourses.value = courses
        ack.acknowledged(SyncField.color(key("XA0000001")), serverHlc + 10, signIn)
        val step = reconcilers()[2]

        step.reconcile(pass(snapshot(), SyncMode.FOREGROUND))
        assertEquals("#ABCDEF", state.allCourses.value.single().customColorHex)

        val later = snapshot {
            it.put("server_hlc", serverHlc + 20)
            it.put("course_overrides", org.json.JSONArray())
        }
        step.reconcile(pass(later, SyncMode.FOREGROUND))
        assertEquals(null, state.allCourses.value.single().customColorHex)
        assertEquals(null, w.dataCache.loadCourses(term).single().customColorHex)
    }

    /**
     * `tigersync-merge`, "Edit while a write is in flight": a red pick being sent, then blue picked
     * before its reply; a pull meanwhile keeps blue, which then goes up after red's reply.
     */
    @Test
    fun `an edit made while a write is in flight is kept by the pull and sent`() = runBlocking {
        val courses = listOf(course("XA0000001", "#0000FF"))
        w.dataCache.saveCourses(courses, term, signIn)
        state.allCourses.value = courses
        val path = "PATCH /v4/sync/courses/${key("XA0000001")}/override"
        val red = backend.hold(path)
        val first = appScope.async { overrides.send("${term}XA0000001", colorHex = "#FF0000", semester = term, courseNo = "XA0000001", signIn = signIn) }
        withTimeout(10_000) { red.arrived.await() }
        val second = appScope.async { overrides.send("${term}XA0000001", colorHex = "#0000FF", semester = term, courseNo = "XA0000001", signIn = signIn) }
        // Kept, its send waiting for red's reply.
        withTimeout(10_000) { while (overrides.heldFields().isEmpty() || waitingColors() != listOf("#0000FF")) kotlinx.coroutines.delay(5) }

        reconcilers()[2].reconcile(pass(snapshot(), SyncMode.FOREGROUND))
        assertEquals("kept over the snapshot's", "#0000FF", state.allCourses.value.single().customColorHex)

        red.release(200)
        withTimeout(10_000) {
            first.await()
            second.await()
        }
        assertEquals(listOf("#FF0000", "#0000FF"), backend.received(path).map { JSONObject(it.body).getString("color_hex") })
    }

    private suspend fun waitingColors(): List<String?> = w.dataCache.loadPendingCourseOverrides().map { it.colorHex }

    /** [block] off the main thread, which the background's holidays step posts to, idled meanwhile. */
    private fun offMain(block: suspend () -> Unit) {
        val thread = Executors.newSingleThreadExecutor()
        try {
            val run = thread.submit(Callable { runBlocking { block() } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!run.isDone) {
                check(System.nanoTime() < deadline) { "the background pass never finished" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            run.get()
        } finally {
            thread.shutdownNow()
        }
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
