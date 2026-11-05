package org.ntust.app.tigerduck.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The course ignores' queue ([CourseIgnoreSync]) as the app builds it, over the real backend
 * client and real files, against a backend that records what reaches it. On Robolectric, for the
 * reminders a change re-arms.
 *
 * A change goes by its course's key, stamped as it was made, one an older build wrote with no time
 * goes with its old-entry time (Review Focus 1), the reply's clock is the field's acknowledgment,
 * and a reply that says the backend kept a later setting puts it on this phone unless a newer
 * change waits or this phone's setting is no longer the one answered (LWW D12). The queue half is
 * the outbox's course ignores channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseIgnoreChannelTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1141"
    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // TigerSync is off for good on F-Droid, where none of this goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun change(courseNo: String, ignore: Boolean = true, changedAtMs: Long = 1_000L, madeAtMs: Long? = this.madeAtMs, moodleCourseId: Int? = null) =
        PendingCourseIgnore(term, courseNo, "$term$courseNo", moodleCourseId, ignore, changedAtMs, madeAtMs = madeAtMs)

    private suspend fun queue(vararg rows: PendingCourseIgnore) = assertTrue(cache.savePendingCourseIgnores(rows.toList(), signIn))

    private suspend fun waiting() = cache.loadPendingCourseIgnores()

    /**
     * Greptile r1: a change the lane read, then held behind the numeric Moodle id's fill-in, asks
     * the gate again once it has the lock: assignment sync switched off and on meanwhile puts the
     * re-enable check's hold on the ignores, and the change waits for the student's answer.
     */
    @Test
    fun `a change the re-enable check holds while it waits behind the fill-in's request does not go`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = "BBB", courseName = "B", moodleIdNumber = "${term}BBB", moodleNumericCourseId = 41)), term, signIn)
        queue(change("AAA"))
        val sent = mutableListOf<Pair<String?, Boolean?>>()
        val fillOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reachedSend = CompletableDeferred<Unit>()
        var armed = false
        var held = false
        var sendsAsked = 0
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { target, ignore, _, _, _ ->
                sent += target.courseKey to ignore
                if (ignore == null) {
                    fillOut.complete(Unit)
                    release.await()
                }
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to (ignore ?: true)))
            },
            CourseIgnoreEffects {},
            // Once armed, the retry asks as it starts, and its send once more right before the lock.
            sends = {
                if (armed && ++sendsAsked == 2) reachedSend.complete(Unit)
                !held
            },
            clock = { 9_000L },
        )
        val fill = launch { ignores.applySnapshot(snapshot(fetchedAtMs = 9_000L, "BBB" to true), signIn) { false } }
        fillOut.await()
        armed = true
        val retry = launch { ignores.retryPending(signIn) }
        reachedSend.await()
        held = true
        release.complete(Unit)
        fill.join()
        retry.join()

        assertEquals(listOf<Pair<String?, Boolean?>>("client:$term:BBB" to null), sent)
        assertEquals(listOf("$term:AAA" to true), waiting().map { it.key to it.ignore })
    }

    /**
     * Fix round 1: a change the lane read, then held behind the numeric Moodle id's fill-in, which
     * keeps the send lock across its own request, is read again once it has the lock: Use Server
     * took it back meanwhile, and it does not go.
     */
    @Test
    fun `a change Use Server takes back while it waits behind the fill-in's request does not go`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = "BBB", courseName = "B", moodleIdNumber = "${term}BBB", moodleNumericCourseId = 41)), term, signIn)
        queue(change("AAA"))
        val sent = mutableListOf<Pair<String?, Boolean?>>()
        val fillOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reachedSend = CompletableDeferred<Unit>()
        var armed = false
        var sendsAsked = 0
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { target, ignore, _, _, _ ->
                sent += target.courseKey to ignore
                if (ignore == null) {
                    fillOut.complete(Unit)
                    release.await()
                }
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to (ignore ?: true)))
            },
            CourseIgnoreEffects {},
            // Once armed, the retry asks as it starts, and its send once more right before the lock.
            sends = {
                if (armed && ++sendsAsked == 2) reachedSend.complete(Unit)
                true
            },
            clock = { 9_000L },
        )
        val fill = launch { ignores.applySnapshot(snapshot(fetchedAtMs = 9_000L, "BBB" to true), signIn) { false } }
        fillOut.await()
        armed = true
        val retry = launch { ignores.retryPending(signIn) }
        reachedSend.await()
        ignores.adoptServer(null, signIn)
        release.complete(Unit)
        fill.join()
        retry.join()

        assertEquals(listOf<Pair<String?, Boolean?>>("client:$term:BBB" to null), sent)
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    /**
     * Codex on Greptile r1: the gate is asked last, once the change is read again under the sync's
     * lock, right before the request: a re-enable check's hold put on the ignores while that read
     * ran stops the change.
     */
    @Test
    fun `a change the re-enable check holds while it is read again under the sync's lock does not go`() = runTest {
        queue(change("AAA"))
        val sent = mutableListOf<String?>()
        var sendsAsked = 0
        var held = false
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { target, ignore, _, _, _ ->
                sent += target.courseKey
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to ignore))
            },
            CourseIgnoreEffects {},
            // The retry asks as it starts and its send once more; the hold comes on as the change
            // is read again under the lock, so the gate the send asks last, right before the request,
            // finds it.
            sends = {
                if (++sendsAsked >= 3) held = true
                !held
            },
            clock = { 9_000L },
        )

        ignores.retryPending(signIn)

        assertEquals(emptyList<String?>(), sent)
        assertEquals(listOf("$term:AAA" to true), waiting().map { it.key to it.ignore })
    }

    /** Fix round 1: one batch, one time, as each change of a batch always went, however the clock moves as its courses are read. */
    @Test
    fun `every change of a batch goes with the batch's one time`() = runTest {
        var clockMs = 5_000L
        val stamps = mutableListOf<Long?>()
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { _, ignore, _, _, madeAtMs ->
                stamps += madeAtMs
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to ignore))
            },
            CourseIgnoreEffects {},
            sends = { true },
            clock = { clockMs++ },
        )
        cache.saveIgnoredCourses(setOf("$term:AAA", "$term:BBB"), signIn)

        ignores.pushLocal(null, signIn)

        assertEquals(2, stamps.size)
        assertEquals("one time for the batch: $stamps", 1, stamps.toSet().size)
    }

    /**
     * A newer tap made while the older is out goes once, however many drains run: the
     * older tap's drain does not send it again after the newer tap's own sent it, and its refusal is
     * counted once.
     */
    @Test
    fun `a newer tap made while the older is out goes once, though it is refused`() = runTest {
        val sent = mutableListOf<Boolean?>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var clockMs = 1_000L
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { _, ignore, _, _, _ ->
                sent += ignore
                if (sent.size > 1) throw PushApiException("patchCourseIgnore failed: HTTP 422", 422)
                firstOut.complete(Unit)
                release.await()
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to ignore))
            },
            CourseIgnoreEffects {},
            sends = { true },
            clock = { clockMs++ },
        )

        val first = launch { ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = true, signIn = signIn) }
        firstOut.await()
        val second = launch { ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = false, signIn = signIn) }
        testScheduler.advanceUntilIdle()
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf<Boolean?>(true, false), sent)
        assertEquals("refused once", listOf(1), waiting().map { it.attempts })
    }

    /**
     * A newer tap goes once, though a drain already under way sends it while the tap's reminders are
     * re-armed, before the tap's own drain has started: that drain is under way from before the
     * tap is kept.
     */
    @Test
    fun `a newer tap goes once though a drain under way sends it while its reminders are re-armed`() = runTest {
        val sent = mutableListOf<Boolean?>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newerSent = CompletableDeferred<Unit>()
        var armed = false
        var clockMs = 1_000L
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { _, ignore, _, _, _ ->
                sent += ignore
                if (ignore == true) {
                    firstOut.complete(Unit)
                    release.await()
                    return@CourseIgnoreWriter WriteAnswer(sent = true, serverClockUs = null, applied = null, values = mapOf("ignore_assignments" to ignore))
                }
                newerSent.complete(Unit)
                throw PushApiException("patchCourseIgnore failed: HTTP 422", 422)
            },
            // Once armed, the newer tap is kept: the older's reply comes, and its drain sends the
            // newer tap, while the reminders are re-armed.
            CourseIgnoreEffects {
                if (armed) {
                    armed = false
                    release.complete(Unit)
                    newerSent.await()
                }
            },
            sends = { true },
            clock = { clockMs++ },
        )

        val first = launch { ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = true, signIn = signIn) }
        firstOut.await()
        armed = true
        ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = false, signIn = signIn)
        first.join()

        assertEquals(listOf<Boolean?>(true, false), sent)
        assertEquals("refused once", listOf(1), waiting().map { it.attempts })
    }

    // --- /v4 ---

    private val v4 = "http://127.0.0.1:9/v4"
    private val v4Backend = RecordingBackend()

    /** The wall clock the sync clock and the old-entry time read; a test moves it. */
    private var nowMs = 1_790_000_500_000L
    private val syncClock = SyncClock(SyncClockStore(context.getSharedPreferences("sync-clock", 0)), { nowMs })
    private val ack = AckClock()
    private val oldEntryTime = OldEntryTime(context.getSharedPreferences("tigerduck_outbox", 0), Dispatchers.Unconfined) { nowMs }
    private val queueFile = File(context.filesDir, "TigerDuckData/pending_course_ignores.json")

    /** The sync as the app builds it, over the client at `/v4`, stamping with [syncClock]. */
    private fun TestScope.v4Ignores() = CourseIgnoreSync(
        cache, pushApiClientAt(v4, v4Backend.client, prefs, syncClock), prefs, AssignmentNotificationScheduler(context),
        syncClock = syncClock, ack = ack, scope = backgroundScope, oldEntryTime = oldEntryTime,
    )

    private fun v4Patch(courseNo: String, body: String) = listOf("PATCH", "$v4/sync/courses/client:$term:$courseNo/override", listOf(bearer), json, body)

    private fun v4PathOf(courseNo: String) = "/v4/sync/courses/client:$term:$courseNo/override"

    private fun field(courseNo: String) = SyncField.ignore(SyncField.courseKey(term, courseNo))

    /** A reply that the backend kept a later setting: not ignored. */
    private val lost = """{"id":41,"color_hex":null,"custom_names":{},"ignore_assignments":false,"updated_at":"2026-10-25T00:39:59+00:00",""" +
        """"color_hex_hlc":null,"custom_names_hlc":{},"ignore_assignments_hlc":1790000900000000,"applied":[]}"""

    /** This build first ran at [firstRunMs]; an older build wrote the queue file [rows] at [fileMs]. */
    private fun olderBuildWrote(rows: String, fileMs: Long, firstRunMs: Long) {
        val now = nowMs
        nowMs = firstRunMs
        oldEntryTime.recordFirstRun()
        nowMs = now
        queueFile.parentFile?.mkdirs()
        queueFile.writeText(rows)
        assertTrue(queueFile.setLastModified(fileMs))
    }

    @Test
    fun `on v4 a change goes by its course's key, stamped as it was made, and its reply's clock acknowledges it`() = runTest {
        queue(change("AAA", moodleCourseId = 7))
        v4Backend.answer(v4PathOf("AAA"), 200, syncFixture("v4/answer-course-override.json"), mapOf("X-TigerSync-Clock" to "$CLOCK_US"))

        v4Ignores().retryPending(signIn)

        assertEquals(listOf(v4Patch("AAA", """{"ignore_assignments":true,"moodle_course_id":7,"hlc":1790000000123000}""")), v4Backend.sent.map { it.shape() })
        assertEquals(CLOCK_US, ack.lastAck(field("AAA")))
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    /** Review Focus 1: never its send time, and never refused for want of a stamp. */
    @Test
    fun `an entry an older build wrote goes out on v4 stamped with its old-entry time`() = runTest {
        olderBuildWrote(
            """[{"semester":"$term","courseNo":"AAA","moodleId":"${term}AAA","ignore":true,"changedAtMs":1000,"attempts":0}]""",
            fileMs = FILE_MS, firstRunMs = FIRST_RUN_MS,
        )
        val ignores = v4Ignores()

        // Given its time at its first read under the sign-in, and written back, though nothing goes yet.
        prefs.syncCourses = false
        ignores.forget(signIn) { false }
        assertEquals(listOf<Long?>(FILE_MS), waiting().map { it.madeAtMs })
        nowMs += 60_000
        prefs.syncCourses = true
        ignores.retryPending(signIn)

        assertEquals(listOf(v4Patch("AAA", """{"ignore_assignments":true,"hlc":${FILE_MS * 1_000}}""")), v4Backend.sent.map { it.shape() })
    }

    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        prefs.syncAssignments = false
        val ignores = v4Ignores()

        ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = true, signIn = signIn)
        ignores.retryPending(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())

        prefs.syncAssignments = true
        v4Backend.answer(v4PathOf("AAA"), 200, syncFixture("v4/answer-course-override.json"))
        ignores.retryPending(signIn)

        assertEquals(listOf("PATCH", "$v4/sync/courses/client:$term:AAA/override"), v4Backend.sent.single().shape().take(2))
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    @Test
    fun `a refused v4 write applies the backend's value unless a newer edit waits`() = runTest {
        cache.saveIgnoredCourses(setOf("$term:AAA", "$term:BBB"), signIn)
        queue(change("AAA"), change("BBB", changedAtMs = 2_000L))
        v4Backend.answer(v4PathOf("AAA"), 200, lost)
        v4Backend.answer(v4PathOf("BBB"), 200, lost)
        val ignores = v4Ignores()

        ignores.retryPending(signIn)

        assertEquals("each setting is the backend's later one", emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
    }

    /** A tap kept while the reply is out, or this phone's setting changed since, keeps its value. */
    @Test
    fun `a refused v4 write leaves a newer tap, waiting or kept, as it is`() = runTest {
        cache.saveIgnoredCourses(setOf("$term:AAA", "$term:BBB"), signIn)
        lateinit var ignores: CourseIgnoreSync
        val sent = mutableListOf<String>()
        var effects = 0
        ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { target, _, _, pinned, _ ->
                sent += target.courseKey.orEmpty()
                if (target.courseKey == "client:$term:AAA" && sent.size == 1) {
                    // AAA tapped off and on again while its PATCH is out, the second offline.
                    prefs.syncCourses = false
                    ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = false, signIn = pinned)
                    ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = true, signIn = pinned)
                    prefs.syncCourses = true
                } else if (target.courseKey == "client:$term:BBB") {
                    // BBB's setting changed here and its change waits no longer: not the one answered.
                    cache.saveIgnoredCourses(cache.loadIgnoredCourses() - "$term:BBB", pinned)
                } else {
                    // The newer tap, which the backend takes.
                    return@CourseIgnoreWriter WriteAnswer(sent = true, serverClockUs = null, applied = setOf("ignore_assignments"), values = mapOf("ignore_assignments" to true))
                }
                WriteAnswer(sent = true, serverClockUs = null, applied = emptySet(), values = mapOf("ignore_assignments" to false))
            },
            CourseIgnoreEffects { effects++ },
            sends = { CourseIgnoreSync.syncsCourseIgnores(prefs) },
            clock = { nowMs++ },
            outbox = IgnoreOutbox({ nowMs++ }, AckClock(), backgroundScope, null),
        )
        queue(change("AAA"), change("BBB", changedAtMs = 2_000L))

        ignores.retryPending(signIn)

        assertEquals(setOf("$term:AAA"), cache.loadIgnoredCourses())
        assertEquals("the two taps alone re-armed the reminders: no reply's setting was written", 2, effects)
    }

    @Test
    fun `on v4 a change refused as ahead once more is a retry, not a refusal`() = runTest {
        queue(change("AAA"))
        repeat(2) { v4Backend.answer(v4PathOf("AAA"), 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${nowMs * 1_000}")) }

        v4Ignores().retryPending(signIn)

        assertEquals(2, v4Backend.sent.size)
        assertEquals(listOf(0), waiting().map { it.attempts })
    }

    /** The gap between the sign-in's end and the wipe: the entries are still on disk, and stay as they are. */
    @Test
    fun `an entry read under an ended sign-in is neither sent nor rewritten`() = runTest {
        olderBuildWrote(
            """[{"semester":"$term","courseNo":"AAA","moodleId":"${term}AAA","ignore":true,"changedAtMs":1000,"attempts":5}]""",
            fileMs = FILE_MS, firstRunMs = FIRST_RUN_MS,
        )
        val before = queueFile.readText()
        val ignores = v4Ignores()

        val ended = series.end()
        ignores.retryPending(ended)
        ignores.channel.requeueParked(ended)
        ignores.setIgnored(term, "BBB", "${term}BBB", null, ignore = true, signIn = ended)
        ignores.queueAsOldest(term, "CCC", "${term}CCC", null, ignore = true, signIn = ended)
        ignores.forget(ended) { true }
        ignores.adoptServer(null, ended)

        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(before, queueFile.readText())
    }

    /** Review Focus 5: a reply that crosses a sign-out lands nowhere, the next student's set included. */
    @Test
    fun `a v4 reply that lands after a sign-out writes and counts nothing for the next student`() = runTest {
        cache.saveIgnoredCourses(setOf("$term:AAA"), signIn)
        val ack = AckClock()
        val ignores = CourseIgnoreSync(
            cache,
            CourseIgnoreWriter { _, _, _, _, _ ->
                // The student signs out, and the next signs in and ignores the same course.
                series.end()
                cache.clearAllUserData()
                series.open()
                cache.saveIgnoredCourses(setOf("$term:AAA"), signIn)
                WriteAnswer(sent = true, serverClockUs = CLOCK_US, applied = emptySet(), values = mapOf("ignore_assignments" to false))
            },
            CourseIgnoreEffects {},
            sends = { true },
            clock = { nowMs },
            outbox = IgnoreOutbox({ nowMs }, ack, backgroundScope, null),
        )
        queue(change("AAA"))

        ignores.retryPending(signIn)

        assertEquals(setOf("$term:AAA"), cache.loadIgnoredCourses())
        assertNull(ack.lastAck(field("AAA")))
        assertEquals(emptyList<PendingCourseIgnore>(), waiting())
        // Nor did the reply mark the course as changed here: the next student's own snapshot is taken.
        ignores.applySnapshot(snapshot(fetchedAtMs = 1L, "AAA" to false), signIn, PullHolds(1L, ack)::keeps)
        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
    }

    /** A snapshot of the courses AAA and BBB whose [ignores] a device set. */
    private fun snapshot(fetchedAtMs: Long, vararg ignores: Pair<String, Boolean>) = BackendSyncResult(
        ignoredIds = emptySet(),
        completedIds = emptySet(),
        courseOverrides = ignores.map { (courseNo, ignore) ->
            CourseOverrideResult(
                moodleCourseId = "$term$courseNo", courseNo = courseNo, colorHex = null, semester = term,
                ignoreAssignments = ignore, ignoreAssignmentsUpdatedAt = "2026-10-13T00:00:00Z",
            )
        },
        serverCourseNos = setOf("AAA", "BBB"),
        serverCourses = listOf(ServerCourse(courseNo = "AAA", courseName = "A", semester = term), ServerCourse(courseNo = "BBB", courseName = "B", semester = term)),
        currentRevision = 0,
        fetchedAtMs = fetchedAtMs,
    )

    /** Each change is stamped by the sync clock, a tap's and Use Local's alike, not by the wall clock. */
    @Test
    fun `on v4 a tap and Use Local's set are stamped by the sync clock`() = runTest {
        val ignores = v4Ignores()
        ignores.setIgnored(term, "AAA", "${term}AAA", null, ignore = true, signIn = signIn)
        cache.saveIgnoredCourses(setOf("$term:AAA", "$term:BBB"), signIn)

        ignores.pushLocal(null, signIn)

        val stamps = v4Backend.sent.map { Regex(""""hlc":(\d+)""").find(it.body.orEmpty())?.groupValues?.get(1)?.toLong() }
        assertEquals(3, stamps.size)
        assertTrue("stamped by the sync clock: $stamps", stamps.all { it != null && it / 1_000 in nowMs..nowMs + 10 })
    }

    /** LWW D13's stamp-0 setting: kept only where none waits, and sent as the oldest. */
    @Test
    fun `a setting queued as the oldest goes stamped 0, and leaves a waiting change as it is`() = runTest {
        val ignores = v4Ignores()
        queue(change("AAA"))

        assertTrue(ignores.queueAsOldest(term, "AAA", "${term}AAA", null, ignore = false, signIn = signIn))
        assertTrue(ignores.queueAsOldest(term, "BBB", "${term}BBB", null, ignore = true, signIn = signIn))
        assertEquals(listOf<Long?>(madeAtMs, 0L), waiting().sortedBy { it.courseNo }.map { it.madeAtMs })
        ignores.retryPending(signIn)

        assertEquals(
            listOf(
                v4Patch("AAA", """{"ignore_assignments":true,"hlc":1790000000123000}"""),
                v4Patch("BBB", """{"ignore_assignments":true,"hlc":0}"""),
            ),
            v4Backend.sent.map { it.shape() },
        )
    }

    /** The fill-in is no user's edit: on v4 it is stamped as it goes. */
    @Test
    fun `on v4 the numeric Moodle id's fill-in goes by the course's key, stamped as it goes`() = runTest {
        cache.saveCourses(listOf(Course(courseNo = "AAA", courseName = "A", moodleIdNumber = "${term}AAA", moodleNumericCourseId = 41)), term, signIn)
        val snapshot = BackendSyncResult(
            ignoredIds = emptySet(),
            completedIds = emptySet(),
            courseOverrides = listOf(
                CourseOverrideResult(
                    moodleCourseId = "${term}AAA", courseNo = "AAA", colorHex = null, semester = term,
                    ignoreAssignments = true, ignoreAssignmentsUpdatedAt = "2026-10-13T00:00:00Z",
                ),
            ),
            serverCourseNos = setOf("AAA"),
            serverCourses = listOf(ServerCourse(courseNo = "AAA", courseName = "A", semester = term)),
            currentRevision = 0,
            fetchedAtMs = 5_000L,
        )

        v4Ignores().applySnapshot(snapshot, signIn) { false }

        val sent = v4Backend.sent.single()
        assertEquals(listOf("PATCH", "$v4/sync/courses/client:$term:AAA/override"), sent.shape().take(2))
        val stamp = Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()
        assertTrue("stamped by the sync clock as it went: ${sent.body}", stamp != null && stamp / 1_000 in nowMs..nowMs + 10)
        assertEquals("""{"moodle_course_id":41,"hlc":$stamp}""", sent.body)
    }

    private companion object {
        // An older build last wrote the queue file then, before this build first ran.
        const val FILE_MS = 1_780_000_000_000L
        const val FIRST_RUN_MS = 1_785_000_000_000L
        const val CLOCK_US = 1_790_000_000_500_000L
    }

    // --- Rules either version keeps, which their /v3 cases pinned, on /v4 ---

    @Test
    fun `on v4 a change the server fails waits uncounted`() = runTest {
        queue(change("AAA"))
        v4Backend.answer(v4PathOf("AAA"), 503, """{"detail":"unavailable"}""")

        v4Ignores().retryPending(signIn)

        assertEquals(1, v4Backend.sent.size)
        assertEquals(listOf(0), waiting().map { it.attempts })
    }

    @Test
    fun `on v4 a change refused five times is parked, and goes once more in the next process only`() = runTest {
        queue(change("AAA"))
        repeat(CourseIgnoreSync.MAX_ATTEMPTS + 3) { v4Backend.answer(v4PathOf("AAA"), 404, """{"error":"course_not_found","message":"made up"}""") }
        val ignores = v4Ignores()

        repeat(CourseIgnoreSync.MAX_ATTEMPTS + 2) { ignores.retryPending(signIn) }
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS, v4Backend.sent.size)
        assertEquals(listOf(CourseIgnoreSync.MAX_ATTEMPTS), waiting().map { it.attempts })

        val next = v4Ignores()
        repeat(2) { next.retryPending(signIn) }
        assertEquals(CourseIgnoreSync.MAX_ATTEMPTS + 1, v4Backend.sent.size)
        assertEquals(listOf(CourseIgnoreSync.MAX_ATTEMPTS), waiting().map { it.attempts })
    }
}
