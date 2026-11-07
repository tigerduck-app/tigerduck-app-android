package org.ntust.app.tigerduck.sync

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.ListenableWorker
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
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.backgroundSyncWorker
import org.ntust.app.tigerduck.notification.settingsSyncAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.homeSyncRig
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Home's full sync, the background worker's and Home's revision poll each send what waits in
 * the six queues one after the other, in [OutboxChannelId]'s order, which is the order they
 * always sent them in: one drain of the outbox for every sync. Driven over the real queues and
 * clients, with one change waiting in each; read from what reaches the backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxDrainWiringTest {

    private val context = RuntimeEnvironment.getApplication()
    private val backend = ScriptedBackend(under = "/v4")
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private lateinit var w: AccountWorld
    private lateinit var rig: HomeSyncRig
    private lateinit var signIn: SignIn

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
        signIn = w.sessions.school.signIns.current
        backend.answer("GET /sync/full", 200, EMPTY_SNAPSHOT)
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    /** One change waiting in each queue, its first send refused where it makes one. */
    private fun oneChangeInEachQueue() = runBlocking {
        val kept = Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = MOODLE_ID)
        val added = Course(courseNo = ADDED, courseName = "Added here", isManual = true)
        w.dataCache.saveCourses(listOf(kept, added), TERM, signIn)
        backend.answer(OVERRIDE, 500)
        rig.ignores.setIgnored(TERM, COURSE, MOODLE_ID, null, ignore = true, signIn = signIn)
        rig.marks.keep("8", "locally_completed", signIn)
        backend.answer("POST /sync/courses", 500)
        rig.adds.send(listOf(kept, added), TERM, ADDED, addedAt = EditTime(1L), signIn = signIn)
        assertTrue(rig.deletes.keep(TERM, GONE, queuedAt = EditTime(5L), signIn = signIn))
        rig.overrides.queue(
            listOf(PendingCourseOverrides.Change(MOODLE_ID, colorHex = "#ABCDEF", semester = TERM, courseNo = COURSE)),
            rig.overrides.reserveVersion(), signIn,
        )
        backend.answer("PUT /sync/holiday-overrides/$HOLIDAY", 500)
        rig.holidays.send(HOLIDAY, signIn)
        backend.endpoints().size
    }

    /** The channel each request after the first [from] is a send of, up to the snapshot's fetch. */
    private fun channelsSentSince(from: Int): List<OutboxChannelId> = backend.endpoints().drop(from)
        .takeWhile { it != "GET /sync/full" }
        .mapIndexed { i, endpoint ->
            val body = backend.received(endpoint).let { all -> all[backend.endpoints().subList(0, from + i).count { it == endpoint }] }.body
            when {
                endpoint == OVERRIDE && "ignore_assignments" in body -> OutboxChannelId.COURSE_IGNORES
                endpoint.startsWith("PATCH /sync/assignments/") -> OutboxChannelId.MARKS
                endpoint == "POST /sync/courses/upload" && "\"adds\"" in body -> OutboxChannelId.COURSE_ADDS
                endpoint.startsWith("DELETE /sync/courses/") -> OutboxChannelId.COURSE_DELETES
                endpoint == OVERRIDE && "color_hex" in body -> OutboxChannelId.COURSE_OVERRIDES
                endpoint.startsWith("PUT /sync/holiday-overrides/") -> OutboxChannelId.HOLIDAYS
                else -> throw AssertionError("not a send of what waits: $endpoint $body")
            }
        }

    @Test
    fun `Home's full sync sends the six channels in the outbox's order before its snapshot`() {
        val from = oneChangeInEachQueue()

        runBlocking { rig.sync.pull(homeState(), signIn) }

        assertEquals(OutboxChannelId.entries, channelsSentSince(from))
        assertTrue("the snapshot was not fetched", "GET /sync/full" in backend.endpoints().drop(from))
    }

    @Test
    fun `Home's revision poll sends the six channels in the outbox's order`() {
        val from = oneChangeInEachQueue()

        runBlocking { rig.sync.sendWhatWaits(signIn) }

        assertEquals(OutboxChannelId.entries, channelsSentSince(from))
        assertEquals("a poll fetches no snapshot", OutboxChannelId.entries.size, backend.endpoints().size - from)
    }

    @Test
    fun `the background worker sends the six channels in the outbox's order before its snapshot`() {
        val from = oneChangeInEachQueue()
        // The school's data was fetched a minute ago, so only the backend sync is due.
        val aMinuteAgo = System.currentTimeMillis() - 60_000
        w.academicCalendar.current().currentTerm(AppClock.localDateTime().toLocalDate())
            ?.let { w.appPrefs.school.backgroundCoursesTerm.set(it.code, signIn) }
        w.appPrefs.school.backgroundCoursesSyncedAtMs.set(aMinuteAgo, signIn)
        w.appPrefs.school.schoolDataSyncedAtMs.set(aMinuteAgo, signIn)
        val worker = backgroundSyncWorker(context, w, rig, settingsSyncAt(context, w, backend.client, idleScope), idleScope)

        val thread = Executors.newSingleThreadExecutor()
        try {
            val run = thread.submit(Callable { runBlocking { worker.doWork() } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!run.isDone) {
                check(System.nanoTime() < deadline) { "the background sync never finished" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(2)
            }
            assertEquals(ListenableWorker.Result.success(), run.get())
        } finally {
            thread.shutdownNow()
        }

        assertEquals(OutboxChannelId.entries, channelsSentSince(from))
        assertTrue("the snapshot was not fetched", "GET /sync/full" in backend.endpoints().drop(from))
    }

    private fun homeState() = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(listOf(Course(courseNo = COURSE, courseName = "Kept", moodleIdNumber = MOODLE_ID))),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    private companion object {
        const val STUDENT = "B00015999"
        const val TERM = "1141"
        const val COURSE = "CS1001"
        const val ADDED = "AD1001"
        const val GONE = "GO1001"
        const val MOODLE_ID = "1141CS1001"
        const val HOLIDAY = 3

        // A course's override, by its key: its color and whether its assignments are ignored.
        const val OVERRIDE = "PATCH /sync/courses/client:$TERM:$COURSE/override"

        val EMPTY_SNAPSHOT = JSONObject()
            .put("assignments", JSONArray())
            .put("assignment_overrides", JSONArray())
            .put("courses", JSONArray())
            .put("course_overrides", JSONArray())
            .put("current_revision", 42)
            .toString()
    }
}
