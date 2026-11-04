package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.io.File

/**
 * The course colors and names' queue ([PendingCourseOverrides]) over the real backend client and
 * real files, against a backend that records what reaches it.
 *
 * A change goes by its course's key, stamped as it was made, a change an older build wrote with no
 * time goes with its old-entry time (Review Focus 1), one made with its kind off is kept and goes
 * once it is on, the reply's clock is the field's acknowledgment, and a reply that says the
 * backend kept a later value puts it on this phone unless a newer change waits or this phone's
 * value is no longer the one answered (LWW D12).
 */
class CourseOverridesChannelTest {

    // The endpoint setting as the released build left it, which speaks /v4.
    private val setting = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { setting }))
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val calculus = Course(courseNo = "MA1010002", courseName = "Calculus", moodleIdNumber = "1151MA1010002")
    private val physics = Course(courseNo = "PH1010003", courseName = "Physics", moodleIdNumber = "1151PH1010003")
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

    /** The queue over the client at the [setting], with no sync clock or old-entry time of its own. */
    private fun plainOverrides() = PendingCourseOverrides(cache, pushApiClientAt(setting, backend.client, prefs), prefs)

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun pathOf(course: Course) = "/v4/sync/courses/client:$term:${course.courseNo}/override"

    private val notFound = """{"error":"course_not_found","message":"No course has this key."}"""

    private fun color(course: Course, hex: String, madeAtMs: Long? = this.madeAtMs, semester: String? = null) = PendingCourseOverride(
        course.moodleIdNumber, hex, null, null, changedAtMs = 1_000L,
        semester = semester, courseNo = semester?.let { course.courseNo }, seq = 1L, madeAtMs = madeAtMs,
    )

    private fun name(course: Course, name: String, locale: String, madeAtMs: Long? = this.madeAtMs) = PendingCourseOverride(
        course.moodleIdNumber, null, name, locale, changedAtMs = 1_000L, seq = 1L, madeAtMs = madeAtMs,
    )

    private suspend fun queue(vararg rows: PendingCourseOverride) = assertTrue(cache.savePendingCourseOverrides(rows.toList(), signIn))

    private suspend fun waiting() = cache.loadPendingCourseOverrides()

    // --- Parked, not let go (spec §5.3 race 3, refactor-v4 R3; amended with xinshou, Telegram 1663) ---

    private val max = PendingCourseOverrides.MAX_ATTEMPTS

    /** Recorded with its term and number, so a 404 once its course is gone lets it go. */
    @Test
    fun `a pick that names no term records the current term's course with its Moodle id`() = runTest {
        val current = cache.currentSemesterCode()
        cache.saveCourses(listOf(calculus), current, signIn)
        // The colors' re-enable check holds them, so both wait.
        prefs.school.pendingConflictCategories.set(setOf(PendingCourseOverrides.category(color = true)), testSignIn())

        plainOverrides().send(calculus.moodleIdNumber!!, colorHex = "#FF0000", signIn = signIn)
        plainOverrides().send(physics.moodleIdNumber!!, colorHex = "#00FF00", signIn = signIn)

        assertEquals(
            listOf<Pair<String?, String?>>(current to calculus.courseNo, null to null),
            waiting().map { it.semester to it.courseNo },
        )
    }

    @Test
    fun `on v4 a change whose course key cannot be built parks until a course upload lands`() = runTest {
        val current = cache.currentSemesterCode()
        // A Moodle id that names no term, so the id itself gives no key either (iOS's termAndNumber).
        val calculus = calculus.copy(moodleIdNumber = "45510")
        queue(name(calculus, "Calc", "en"))
        val overrides = v4Overrides()

        repeat(2) { overrides.drain(signIn) }
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(listOf(max), waiting().map { it.attempts })

        // The course reaches this phone's current term, and an upload lands.
        cache.saveCourses(listOf(calculus), current, signIn)
        Outbox(setOf(overrides)).courseUploadLanded(signIn)
        overrides.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/client:$current:MA1010002/override"), v4Backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /**
     * Use Local's name held back on v4 goes into the queue with a Moodle id derived from its term
     * and number, which names no cached course: a 404 for it parks it while its course is here,
     * and lets it go once the course has left.
     */
    @Test
    fun `a name Use Local held back on v4 parks on a 404 while its course is here, and goes once it has left`() = runTest {
        val typed = Course(courseNo = "XA0000009", courseName = "Typed", isManual = true)
        val derived = "$term${typed.courseNo}"
        cache.saveCourses(listOf(typed), term, signIn)
        val overrides = v4Overrides()
        overrides.queue(
            listOf(PendingCourseOverrides.Change(derived, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo)),
            overrides.reserveVersion(), signIn,
        )
        repeat(2) { v4Backend.answer("/v4/sync/courses/client:$term:${typed.courseNo}/override", 404, """{"error":"course_not_found","message":"No such course."}""") }

        overrides.drain(signIn)
        assertEquals(listOf(derived to max), waiting().map { it.moodleId to it.attempts })

        cache.saveCourses(emptyList(), term, signIn)
        Outbox(setOf(overrides)).courseUploadLanded(signIn)
        overrides.drain(signIn)

        assertEquals(2, v4Backend.sent.size)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /**
     * A course added by hand and renamed before its add's upload landed: the rename's 404 parks it
     * through every sync the upload keeps failing in, and the upload's landing sends it.
     */
    @Test
    fun `a rename of a course added by hand waits for its add's upload, however many syncs that takes`() = runTest {
        val typed = Course(courseNo = "XA0000009", courseName = "Typed", isManual = true, moodleIdNumber = "1151XA0000009")
        cache.saveCourses(listOf(typed), term, signIn)
        lateinit var outbox: Outbox
        lateinit var overrides: PendingCourseOverrides
        val client = pushApiClientAt(setting, backend.client, prefs, uploadNews = CourseUploadNews(dagger.Lazy { overrides }, dagger.Lazy { outbox }))
        val adds = PendingCourseAdds(cache, client, SyncPolicies(prefs))
        overrides = PendingCourseOverrides(cache, client, prefs)
        outbox = Outbox(setOf(adds, overrides))
        val upload = "/v4/sync/courses/upload"
        repeat(max + 1) { backend.answer(upload, 503, """{"detail":"unavailable"}""") }
        repeat(2) { backend.answer(pathOf(typed), 404, notFound) }

        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs, signIn = signIn)
        overrides.send(typed.moodleIdNumber!!, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo, signIn = signIn)
        repeat(max) { outbox.drain(signIn) }
        // Once as it was made, once more at the process's first sync, and parked since.
        assertEquals(2, backend.sent.count { it.url.endsWith("/override") })

        backend.sent.clear()
        // The upload lands, and the same sync sends the rename after it.
        backend.answer(upload, 200, """{"accepted_keys":["client:$term:${typed.courseNo}"]}""")
        outbox.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/upload", "$v4/sync/courses/client:$term:XA0000009/override"), backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** The process's one more try is a sign-in's: the next student's parked changes get their own. */
    @Test
    fun `a sign-out gives the next student's parked changes their own try in this process`() = runTest {
        val overrides = plainOverrides()
        val outbox = Outbox(setOf(overrides))
        overrides.drain(signIn)
        series.end()
        outbox.forgetAccount()
        cache.clearAllUserData()
        series.open()
        queue(color(calculus, "#FF0000").copy(attempts = max))

        overrides.drain(signIn)

        assertEquals(1, backend.sent.size)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** Work of an ended sign-in that runs after its sign-out takes nothing of the next student's. */
    @Test
    fun `an ended sign-in's retry leaves the next student's try in this process`() = runTest {
        val overrides = plainOverrides()
        val ended = series.end()
        Outbox(setOf(overrides)).forgetAccount()
        cache.clearAllUserData()
        overrides.drain(ended)
        series.open()
        queue(color(calculus, "#FF0000").copy(attempts = max))

        overrides.drain(signIn)

        assertEquals(1, backend.sent.size)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /**
     * Greptile r1: a course upload that lands while a change's PATCH is out cannot be missed by
     * that PATCH's 404, which the backend gave before the row was made: the change goes at the
     * next sync rather than waiting, parked, for another upload.
     */
    @Test
    fun `an upload that lands while a change's PATCH is out leaves it due though that PATCH gets a 404`() = runTest {
        cache.saveCourses(listOf(calculus), term, signIn)
        var calls = 0
        val out = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val overrides = PendingCourseOverrides(
            cache,
            CourseOverrideWriter { _, _, _, _ ->
                if (++calls == 1) {
                    out.complete(Unit)
                    reply.await()
                    throw BackendException(BackendError(404, "course_not_found"), "patchCourseOverride failed: HTTP 404")
                }
                TOOK
            },
            records = { true },
            sends = { true },
        )
        val outbox = Outbox(setOf(overrides))
        // The process's one more try is spent, with nothing parked.
        overrides.drain(signIn)

        val pick = launch { overrides.send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn) }
        out.await()
        outbox.courseUploadLanded(signIn)
        reply.complete(Unit)
        pick.join()
        overrides.drain(signIn)

        assertEquals(2, calls)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /**
     * A sender whose first PATCH of [held] is out ([out]) until [reply] completes, each PATCH
     * recorded in [sent] and answered 404 `course_not_found` unless [stored] says it lands.
     */
    private fun holding(held: String, out: CompletableDeferred<Unit>, reply: CompletableDeferred<Unit>, sent: MutableList<String>, stored: (String) -> Boolean) =
        CourseOverrideWriter { change, _, _, _ ->
            val moodleId = change.moodleId.orEmpty()
            sent += moodleId
            if (moodleId == held && !out.isCompleted) {
                out.complete(Unit)
                reply.await()
            }
            if (!stored(moodleId)) throw BackendException(BackendError(404, "course_not_found"), "patchCourseOverride failed: HTTP 404")
            TOOK
        }

    /** Codex on Greptile r1: the wake is the lane's, which a caller cancelled before the 404 does not skip. */
    @Test
    fun `an upload that lands while a change's PATCH is out leaves it due though its caller is cancelled before the 404`() = runTest {
        cache.saveCourses(listOf(calculus), term, signIn)
        val sent = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val overrides = PendingCourseOverrides(cache, holding(calculus.moodleIdNumber!!, out, reply, sent) { sent.size > 1 }, records = { true }, sends = { true })
        val outbox = Outbox(setOf(overrides))
        // The process's one more try is spent, with nothing parked.
        overrides.drain(signIn)

        val pick = launch { overrides.send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn) }
        out.await()
        outbox.courseUploadLanded(signIn)
        pick.cancel()
        reply.complete(Unit)
        pick.join()
        overrides.drain(signIn)

        assertEquals(List(2) { calculus.moodleIdNumber }, sent)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /**
     * Codex on Greptile r1: an upload that left a change's course out as tombstoned while its PATCH
     * was out wakes nothing: its 404 parks it, and the next answer that skips the course lets it go.
     */
    @Test
    fun `an upload that skips a change's course while its PATCH is out leaves the 404 to park it`() = runTest {
        cache.saveCourses(listOf(calculus), term, signIn)
        val sent = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val overrides = PendingCourseOverrides(cache, holding(calculus.moodleIdNumber!!, out, reply, sent) { false }, records = { true }, sends = { true })
        val outbox = Outbox(setOf(overrides))
        overrides.drain(signIn)

        val pick = launch { overrides.send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn) }
        out.await()
        overrides.courseUploadAnswered(term, accepted = emptyList(), skipped = listOf(calculus.courseNo), signIn = signIn)
        outbox.courseUploadLanded(signIn)
        reply.complete(Unit)
        pick.join()
        overrides.drain(signIn)

        assertEquals(listOf(calculus.moodleIdNumber), sent)
        assertEquals(listOf(max), waiting().map { it.attempts })
        overrides.courseUploadAnswered(term, accepted = emptyList(), skipped = listOf(calculus.courseNo), signIn = signIn)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** Codex on Greptile r1: one upload is one more try for each change, though a drain is out as it lands. */
    @Test
    fun `one upload that lands while a drain is out gives a parked change one more try, not two`() = runTest {
        cache.saveCourses(listOf(calculus, physics), term, signIn)
        val sent = mutableListOf<String>()
        val out = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val overrides = PendingCourseOverrides(
            cache,
            holding(calculus.moodleIdNumber!!, out, reply, sent) { it == calculus.moodleIdNumber },
            records = { true },
            sends = { true },
        )
        val outbox = Outbox(setOf(overrides))
        // The physics color parks at its 404 at the process's first retry, which spends the one more try.
        queue(color(physics, "#00FF00", semester = term))
        overrides.drain(signIn)
        assertEquals(listOf(max), waiting().map { it.attempts })

        queue(*waiting().toTypedArray(), color(calculus, "#FF0000", semester = term))
        val sync = launch { overrides.drain(signIn) }
        out.await()
        outbox.courseUploadLanded(signIn)
        reply.complete(Unit)
        sync.join()
        overrides.drain(signIn)

        assertEquals(listOf(physics.moodleIdNumber, calculus.moodleIdNumber, physics.moodleIdNumber), sent)
        assertEquals(listOf(max), waiting().map { it.attempts })
    }

    // --- Let go when the upload that should have made its course skipped it (xinshou, Telegram 1663) ---

    private val typed = Course(courseNo = "XA0000009", courseName = "Typed", isManual = true, moodleIdNumber = "1151XA0000009")
    private val other = Course(courseNo = "XA0000010", courseName = "Other", isManual = true, moodleIdNumber = "1151XA0000010")
    private val upload = "/v4/sync/courses/upload"

    /** The adds and the colors and names as the app wires them, in one outbox their client tells of its uploads. */
    private fun addsAndOverrides(): Triple<Outbox, PendingCourseAdds, PendingCourseOverrides> {
        lateinit var outbox: Outbox
        lateinit var overrides: PendingCourseOverrides
        val client = pushApiClientAt(setting, backend.client, prefs, uploadNews = CourseUploadNews(dagger.Lazy { overrides }, dagger.Lazy { outbox }))
        val adds = PendingCourseAdds(cache, client, SyncPolicies(prefs))
        overrides = PendingCourseOverrides(cache, client, prefs)
        outbox = Outbox(setOf(adds, overrides))
        return Triple(outbox, adds, overrides)
    }

    /** An upload's answer that left [typed] out, its add refused as a hide elsewhere is newer. */
    private fun skipped() =
        """{"upserted":0,"skipped_tombstoned":1,"accepted_keys":[],""" +
            """"adds":[{"course_key":"client:$term:${typed.courseNo}","applied":false,"hlc":1790000900000000}]}"""

    /**
     * The rename parked for its course's upload, which then leaves the course out as tombstoned (a
     * hide elsewhere is newer than the add): no upload will make its row, so the rename goes.
     */
    @Test
    fun `a parked rename goes when the upload that should have made its course skips it as tombstoned`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val (outbox, adds, overrides) = addsAndOverrides()
        backend.answer(upload, 503, """{"detail":"unavailable"}""")
        backend.answer(pathOf(typed), 404, notFound)
        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs, signIn = signIn)
        overrides.send(typed.moodleIdNumber!!, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo, signIn = signIn)
        assertEquals(listOf(max), waiting().map { it.attempts })

        backend.sent.clear()
        backend.answer(upload, 200, skipped())
        outbox.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/upload"), backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** Skipped before its rename was refused: the rename parks at its 404, and goes at the next upload's landing. */
    @Test
    fun `a rename refused after its course's upload skipped it goes at the next upload's landing`() = runTest {
        cache.saveCourses(listOf(typed, other), term, signIn)
        val (outbox, adds, overrides) = addsAndOverrides()
        backend.answer(upload, 200, skipped())
        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs, signIn = signIn)
        backend.answer(pathOf(typed), 404, notFound)
        overrides.send(typed.moodleIdNumber!!, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo, signIn = signIn)
        assertEquals(listOf(max), waiting().map { it.attempts })

        backend.sent.clear()
        // Another course's add lands.
        backend.answer(upload, 200, """{"accepted_keys":["client:$term:${other.courseNo}"]}""")
        adds.send(listOf(other), term, other.courseNo, addedAt = madeAtMs, signIn = signIn)
        outbox.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/upload"), backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** Only an answer that says it left courses out and names the ones it took tells which. */
    @Test
    fun `a rename stays parked for its try when the upload does not say which courses it skipped`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        val (outbox, adds, overrides) = addsAndOverrides()
        backend.answer(upload, 503, """{"detail":"unavailable"}""")
        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs, signIn = signIn)
        backend.answer(pathOf(typed), 404, notFound)
        overrides.send(typed.moodleIdNumber!!, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo, signIn = signIn)

        backend.sent.clear()
        backend.answer(upload, 200, """{"skipped_tombstoned":1}""")
        outbox.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/upload", "$v4/sync/courses/client:$term:XA0000009/override"), backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** A course an upload left out, and a later upload took, is no longer skipped: a rename parked since gets its try. */
    @Test
    fun `a rename parked after its course was skipped and then taken gets its try at the next landing`() = runTest {
        cache.saveCourses(listOf(typed, other), term, signIn)
        val (outbox, adds, overrides) = addsAndOverrides()
        backend.answer(upload, 200, skipped())
        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs, signIn = signIn)
        backend.answer(upload, 200, """{"skipped_tombstoned":0,"accepted_keys":["client:$term:${typed.courseNo}"]}""")
        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs + 1, signIn = signIn)
        backend.answer(pathOf(typed), 404, notFound)
        overrides.send(typed.moodleIdNumber!!, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo, signIn = signIn)
        assertEquals(listOf(max), waiting().map { it.attempts })

        backend.sent.clear()
        backend.answer(upload, 200, """{"accepted_keys":["client:$term:${other.courseNo}"]}""")
        adds.send(listOf(other), term, other.courseNo, addedAt = madeAtMs, signIn = signIn)
        outbox.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/upload", "$v4/sync/courses/client:$term:XA0000009/override"), backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** What an upload told one student is not the next student's. */
    @Test
    fun `a skip an upload told the student before leaves the next student's parked change of the course`() = runTest {
        val (outbox, _, overrides) = addsAndOverrides()
        overrides.courseUploadAnswered(term, emptyList(), listOf(typed.courseNo), signIn)
        series.end()
        outbox.forgetAccount()
        cache.clearAllUserData()
        series.open()
        queue(placed(name(typed, "Mine", "en"), typed).copy(attempts = max))

        overrides.courseUploadAnswered(term, listOf(other.courseNo), emptyList(), signIn)

        assertEquals(listOf(max), waiting().map { it.attempts })
    }

    /**
     * Fix round 1: every course upload that lands tells the queue, not the course adds' alone. The
     * class table's timetable fetch uploads the term: the course it takes gets its parked rename
     * sent, and the one it leaves out as tombstoned has its parked rename let go. Both are copies a
     * pull told as the student's own, as an upload leaves out a copy no pull told about.
     */
    @Test
    fun `a parked rename goes after a timetable fetch's upload lands, and is let go when that upload left its course out`() = runTest {
        val told = listOf(typed, other).map { it.copy(isUserAddedOnServer = true) }
        cache.saveCourses(told, term, signIn)
        lateinit var overrides: PendingCourseOverrides
        lateinit var outbox: Outbox
        val client = pushApiClientAt(setting, backend.client, prefs, uploadNews = CourseUploadNews(dagger.Lazy { overrides }, dagger.Lazy { outbox }))
        overrides = PendingCourseOverrides(cache, client, prefs)
        outbox = Outbox(setOf(overrides))
        repeat(2) {
            backend.answer(pathOf(typed), 404, notFound)
            backend.answer(pathOf(other), 404, notFound)
        }
        overrides.send(typed.moodleIdNumber!!, customName = "Mine", locale = "en", semester = term, courseNo = typed.courseNo, signIn = signIn)
        overrides.send(other.moodleIdNumber!!, customName = "Theirs", locale = "en", semester = term, courseNo = other.courseNo, signIn = signIn)
        // The process's one more try is spent: both parked again.
        overrides.drain(signIn)
        assertEquals(listOf(max, max), waiting().map { it.attempts })

        backend.sent.clear()
        backend.answer(upload, 200, """{"upserted":1,"skipped_tombstoned":1,"accepted_keys":["client:$term:${typed.courseNo}"]}""")
        client.uploadCourses(told, term, signIn)
        overrides.drain(signIn)

        assertEquals(listOf("$v4/sync/courses/upload", "$v4/sync/courses/client:$term:XA0000009/override"), backend.sent.map { it.url })
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** Fix round 1: the course adds' upload is told once, by the client that sends it, as every upload is. */
    @Test
    fun `a course add's upload that lands tells the outbox once`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        lateinit var outbox: Outbox
        lateinit var overrides: PendingCourseOverrides
        val client = pushApiClientAt(setting, backend.client, prefs, uploadNews = CourseUploadNews(dagger.Lazy { overrides }, dagger.Lazy { outbox }))
        val adds = PendingCourseAdds(cache, client, SyncPolicies(prefs))
        overrides = PendingCourseOverrides(cache, client, prefs)
        val landings = mutableListOf<SignIn>()
        val probe = object : OutboxChannel {
            override val id = OutboxChannelId.HOLIDAYS
            override suspend fun drain(signIn: SignIn) = Unit
            override suspend fun requeueParked(signIn: SignIn) {
                landings += signIn
            }
            override suspend fun heldFields(): Set<String> = emptySet()
            override fun forgetMemory() = Unit
        }
        outbox = Outbox(setOf(adds, overrides, probe))
        backend.answer(upload, 200, """{"accepted_keys":["client:$term:${typed.courseNo}"]}""")

        adds.send(listOf(typed), term, typed.courseNo, addedAt = madeAtMs, signIn = signIn)

        assertEquals(listOf(signIn), landings)
    }

    /** An upload's answer for an ended sign-in settles nothing of the next student's. */
    @Test
    fun `an upload's skip under an ended sign-in lets go of nothing of the next student's`() = runTest {
        val (_, _, overrides) = addsAndOverrides()
        val ended = signIn
        series.end()
        series.open()
        queue(placed(name(typed, "Mine", "en"), typed).copy(attempts = max))

        overrides.courseUploadAnswered(term, emptyList(), listOf(typed.courseNo), ended)
        overrides.requeueParked(signIn)

        assertEquals(listOf(max - 1), waiting().map { it.attempts })
    }

    /**
     * A newer pick made while the older is out goes once, however many drains run: the
     * older pick's drain does not send it again after the newer pick's own sent it, and its refusal
     * is counted once.
     */
    @Test
    fun `a newer pick made while the older is out goes once, though it is refused`() = runTest {
        val sentColors = mutableListOf<String?>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val overrides = PendingCourseOverrides(
            cache,
            CourseOverrideWriter { change, _, _, _ ->
                sentColors += change.colorHex
                if (sentColors.size > 1) throw PushApiException("patchCourseOverride failed: HTTP 422", 422)
                firstOut.complete(Unit)
                release.await()
                TOOK
            },
            records = { true },
            sends = { true },
        )

        val first = launch { overrides.send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn) }
        firstOut.await()
        val second = launch { overrides.send(calculus.moodleIdNumber!!, colorHex = "#0000FF", semester = term, courseNo = calculus.courseNo, signIn = signIn) }
        withContext(Dispatchers.Default) {
            while (cache.loadPendingCourseOverrides().singleOrNull()?.colorHex != "#0000FF") delay(5)
        }
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("#FF0000", "#0000FF"), sentColors)
        assertEquals("refused once", listOf(1), waiting().map { it.attempts })
    }

    /**
     * A newer pick goes once, though a drain already under way reaches it before the pick's own
     * drain has started: that drain is under way from before the pick is kept.
     */
    @Test
    fun `a newer pick goes once though a drain under way sends it before the pick's own drain starts`() = runTest {
        val sentColors = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newerSent = java.util.concurrent.CountDownLatch(1)
        var armed = false
        val overrides = PendingCourseOverrides(
            cache,
            CourseOverrideWriter { change, _, _, _ ->
                sentColors += change.colorHex
                if (change.colorHex == "#FF0000") {
                    firstOut.complete(Unit)
                    release.await()
                    return@CourseOverrideWriter TOOK
                }
                newerSent.countDown()
                throw PushApiException("patchCourseOverride failed: HTTP 422", 422)
            },
            records = { true },
            // Once armed, the newer pick is kept: the older's reply comes, and its drain sends the
            // newer pick, before this call returns.
            sends = {
                if (armed) {
                    armed = false
                    release.complete(Unit)
                    newerSent.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                true
            },
        )

        // The older pick's drain on threads of its own, so it goes on while the newer pick's call holds this one.
        val first = launch(Dispatchers.Default) { overrides.send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn) }
        firstOut.await()
        armed = true
        overrides.send(calculus.moodleIdNumber!!, colorHex = "#0000FF", semester = term, courseNo = calculus.courseNo, signIn = signIn)
        first.join()
        withContext(Dispatchers.Default) {
            repeat(1_000) { if (sentColors.size < 2 || cache.loadPendingCourseOverrides().singleOrNull()?.attempts == 0) delay(5) }
        }

        assertEquals(listOf("#FF0000", "#0000FF"), sentColors.toList())
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
    private val queueFile = File(context.filesDir, "TigerDuckData/pending_course_overrides.json")

    /** The queue as the app builds it, over the client at `/v4`, stamping with [syncClock]. */
    private fun TestScope.v4Overrides() = PendingCourseOverrides(
        cache, pushApiClientAt(v4, v4Backend.client, prefs, syncClock), prefs,
        syncClock = syncClock, ack = ack, scope = backgroundScope, oldEntryTime = oldEntryTime,
    )

    private fun v4Patch(key: String, body: String) = listOf("PATCH", "$v4/sync/courses/$key/override", listOf(bearer), json, body)

    private fun v4PathOf(course: Course, semester: String = term) = "/v4/sync/courses/client:$semester:${course.courseNo}/override"

    private fun v4Shapes() = v4Backend.sent.map { it.shape() }

    private fun colorField(course: Course, semester: String = term) = SyncField.color(SyncField.courseKey(semester, course.courseNo))

    private fun placed(row: PendingCourseOverride, course: Course, semester: String = term) = row.copy(semester = semester, courseNo = course.courseNo)

    /** A reply that the backend kept a later value: green, and the name Algo in English. */
    private val lost = syncFixture("v4/answer-course-override-lost.json")

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
        queue(placed(color(calculus, "#FF0000"), calculus), placed(name(calculus, "Calc", "en"), calculus))
        v4Backend.answer(v4PathOf(calculus), 200, syncFixture("v4/answer-course-override.json"), mapOf("X-TigerSync-Clock" to "$CLOCK_US"))

        v4Overrides().drain(signIn)

        assertEquals(
            listOf(
                v4Patch("client:1151:MA1010002", """{"color_hex":"#FF0000","hlc":1790000000123000}"""),
                v4Patch("client:1151:MA1010002", """{"custom_name":"Calc","locale":"en","hlc":1790000000123000}"""),
            ),
            v4Shapes(),
        )
        assertEquals(CLOCK_US, ack.lastAck(colorField(calculus)))
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /**
     * The key parts go with the change: its own term and number, though the cached course with its
     * Moodle id is now another. A row an older build wrote with none goes by the current term's
     * course with its Moodle id, and one no course names parks until a course upload lands.
     */
    @Test
    fun `on v4 a change goes to its own row's course, and a row with no term by the current term's course with its Moodle id`() = runTest {
        val current = cache.currentSemesterCode()
        // Cached in no term, and by a Moodle id that names no term (iOS's termAndNumber): it parks.
        val physics = physics.copy(moodleIdNumber = "45511")
        val renumbered = calculus.copy(courseNo = "MA9990002")
        cache.saveCourses(listOf(renumbered), current, signIn)
        queue(placed(color(calculus, "#FF0000"), calculus), name(calculus, "Calc", "en"), name(physics, "Phys", "en"))

        v4Overrides().drain(signIn)

        assertEquals(
            listOf(
                v4Patch("client:1151:MA1010002", """{"color_hex":"#FF0000","hlc":1790000000123000}"""),
                v4Patch("client:$current:MA9990002", """{"custom_name":"Calc","locale":"en","hlc":1790000000123000}"""),
            ),
            v4Shapes(),
        )
        assertEquals(listOf(physics.moodleIdNumber to PendingCourseOverrides.MAX_ATTEMPTS), waiting().map { it.moodleId to it.attempts })
    }

    /** Review Focus 1: never its send time, and never refused for want of a stamp. */
    @Test
    fun `an entry an older build wrote goes out on v4 stamped with its old-entry time`() = runTest {
        olderBuildWrote(
            """[{"moodleId":"1151MA1010002","colorHex":"#FF0000","changedAtMs":1000,"semester":"1151","courseNo":"MA1010002","attempts":0,"seq":5}]""",
            fileMs = FILE_MS, firstRunMs = FIRST_RUN_MS,
        )
        val overrides = v4Overrides()

        // Given its time at its first read under the sign-in, and written back, though nothing goes yet.
        prefs.syncCourseColors = false
        overrides.drain(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(listOf<Long?>(FILE_MS), cache.loadPendingCourseOverrides().map { it.madeAtMs })
        nowMs += 60_000
        prefs.syncCourseColors = true
        overrides.drain(signIn)

        assertEquals(listOf(v4Patch("client:1151:MA1010002", """{"color_hex":"#FF0000","hlc":${FILE_MS * 1_000}}""")), v4Shapes())
    }

    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        prefs.syncCourseNames = false
        val overrides = v4Overrides()

        overrides.send(calculus.moodleIdNumber!!, customName = "Calc", locale = "en", semester = term, courseNo = calculus.courseNo, signIn = signIn)
        overrides.drain(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(listOf("Calc"), waiting().map { it.customName })

        prefs.syncCourseNames = true
        overrides.drain(signIn)

        assertEquals(listOf("PATCH", "$v4/sync/courses/client:1151:MA1010002/override"), v4Backend.sent.single().shape().take(2))
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `a refused v4 write applies the backend's value unless a newer edit waits`() = runTest {
        cache.saveCourses(listOf(calculus.copy(customColorHex = "#FF0000"), physics.copy(customColorHex = "#FF0000")), term, signIn)
        cache.saveCourseCustomNames(mapOf(calculus.courseNo to mapOf("en" to "Calc")), signIn)
        queue(placed(color(calculus, "#FF0000"), calculus), placed(name(calculus, "Calc", "en"), calculus))
        repeat(2) { v4Backend.answer(v4PathOf(calculus), 200, lost) }

        v4Overrides().drain(signIn)

        assertEquals(listOf("#00FF00", "#FF0000"), cache.loadCourses(term).map { it.customColorHex })
        assertEquals(mapOf(calculus.courseNo to mapOf("en" to "Algo")), cache.loadCourseCustomNames())
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** A newer pick queued while the reply is out, or saved here and not queued yet, keeps its value. */
    @Test
    fun `a refused v4 write leaves a newer pick, waiting or saved, as it is`() = runTest {
        cache.saveCourses(listOf(calculus.copy(customColorHex = "#FF0000")), term, signIn)
        cache.saveCourseCustomNames(mapOf(calculus.courseNo to mapOf("en" to "Calc")), signIn)
        lateinit var overrides: PendingCourseOverrides
        var calls = 0
        overrides = PendingCourseOverrides(
            cache,
            CourseOverrideWriter { change, _, pinned, _ ->
                if (calls++ == 0) {
                    // Blue picked while red's PATCH is out, queued and not yet saved here.
                    overrides.queue(listOf(PendingCourseOverrides.Change(calculus.moodleIdNumber!!, colorHex = "#0000FF", semester = term, courseNo = calculus.courseNo)), overrides.reserveVersion(), pinned)
                } else if (change.customName != null) {
                    // A newer name saved here while the older one's PATCH is out.
                    cache.saveCourseCustomNames(mapOf(calculus.courseNo to mapOf("en" to "Calculus")), pinned)
                }
                WriteAnswer(sent = true, serverClockUs = null, applied = emptySet(), values = mapOf("color_hex" to "#00FF00", "custom_names" to mapOf("en" to "Algo")))
            },
            records = { true }, sends = { true },
        )
        queue(placed(color(calculus, "#FF0000"), calculus), placed(name(calculus, "Calc", "en"), calculus))

        overrides.drain(signIn)

        assertEquals(listOf("#FF0000"), cache.loadCourses(term).map { it.customColorHex })
        assertEquals(mapOf(calculus.courseNo to mapOf("en" to "Calculus")), cache.loadCourseCustomNames())
    }

    /**
     * The check that no newer pick waits is made inside the courses' lock, with the write: a pick
     * queued while the reply's value waits for the lock keeps its value.
     */
    @Test
    fun `a pick queued while a refused v4 write waits for the courses' lock keeps its value`() = runTest {
        cache.saveCourses(listOf(calculus.copy(customColorHex = "#FF0000")), term, signIn)
        val replied = CompletableDeferred<Unit>()
        val overrides = PendingCourseOverrides(
            cache,
            CourseOverrideWriter { _, _, _, _ ->
                replied.complete(Unit)
                WriteAnswer(sent = true, serverClockUs = null, applied = emptySet(), values = mapOf("color_hex" to "#00FF00"))
            },
            records = { true }, sends = { true },
        )
        queue(placed(color(calculus, "#FF0000"), calculus))
        val lockHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            cache.updateCourses(term, signIn) { courses, _ ->
                lockHeld.complete(Unit)
                release.await()
                courses
            }
        }
        lockHeld.await()

        val drain = launch(Dispatchers.Default) { overrides.drain(signIn) }
        replied.await()
        // The reply's value now waits for the lock; blue is picked and queued meanwhile.
        withContext(Dispatchers.Default) { delay(300) }
        overrides.queue(listOf(PendingCourseOverrides.Change(calculus.moodleIdNumber!!, colorHex = "#0000FF", semester = term, courseNo = calculus.courseNo)), overrides.reserveVersion(), signIn)
        release.complete(Unit)
        holder.join()
        drain.join()

        assertEquals(listOf("#FF0000"), cache.loadCourses(term).map { it.customColorHex })
    }

    @Test
    fun `on v4 a change refused as ahead once more is a retry, not a refusal`() = runTest {
        queue(placed(color(calculus, "#FF0000"), calculus))
        repeat(2) { v4Backend.answer(v4PathOf(calculus), 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${nowMs * 1_000}")) }

        v4Overrides().drain(signIn)

        assertEquals(2, v4Backend.sent.size)
        assertEquals(listOf(0), waiting().map { it.attempts })
    }

    /** The gap between the sign-in's end and the wipe: the entries are still on disk, and stay as they are. */
    @Test
    fun `an entry read under an ended sign-in is neither sent nor rewritten`() = runTest {
        olderBuildWrote(
            """[{"moodleId":"1151MA1010002","colorHex":"#FF0000","changedAtMs":1000,"semester":"1151","courseNo":"MA1010002","attempts":5,"seq":5}]""",
            fileMs = FILE_MS, firstRunMs = FIRST_RUN_MS,
        )
        val before = queueFile.readText()
        val overrides = v4Overrides()

        val ended = series.end()
        overrides.drain(ended)
        overrides.requeueParked(ended)
        overrides.send(calculus.moodleIdNumber!!, colorHex = "#00FF00", semester = term, courseNo = calculus.courseNo, signIn = ended)
        overrides.queue(listOf(PendingCourseOverrides.Change(physics.moodleIdNumber!!, colorHex = "#0000FF")), overrides.reserveVersion(), ended)
        overrides.queueAsOldest(PendingCourseOverrides.Change(physics.moodleIdNumber!!, customName = "P", locale = "en"), ended)
        overrides.forget(listOf(calculus.moodleIdNumber!!), signIn = ended)
        overrides.adoptServer(listOf("course_colors"), ended)

        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(before, queueFile.readText())
    }

    /** Review Focus 5: a reply that crosses a sign-out lands nowhere, the next student's courses included. */
    @Test
    fun `a v4 reply that lands after a sign-out writes, counts and holds nothing for the next student`() = runTest {
        cache.saveCourses(listOf(calculus.copy(customColorHex = "#FF0000")), term, signIn)
        val ack = AckClock()
        val overrides = PendingCourseOverrides(
            cache,
            CourseOverrideWriter { _, _, _, _ ->
                // The student signs out, and the next signs in and holds the same course, red.
                series.end()
                cache.clearAllUserData()
                series.open()
                cache.saveCourses(listOf(calculus.copy(customColorHex = "#FF0000")), term, signIn)
                WriteAnswer(sent = true, serverClockUs = CLOCK_US, applied = emptySet(), values = mapOf("color_hex" to "#00FF00"))
            },
            records = { true }, sends = { true }, ack = ack,
        )
        queue(placed(color(calculus, "#FF0000"), calculus))

        overrides.drain(signIn)

        assertEquals(listOf("#FF0000"), cache.loadCourses(term).map { it.customColorHex })
        assertNull(ack.lastAck(colorField(calculus)))
        assertEquals(emptySet<String>(), overrides.heldFields())
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    /** LWW D13's stamp-0 change: kept only where none waits, and sent as the oldest. */
    @Test
    fun `a change queued as the oldest goes stamped 0, and leaves a waiting change as it is`() = runTest {
        val overrides = v4Overrides()
        prefs.syncCourseColors = false
        overrides.send(calculus.moodleIdNumber!!, colorHex = "#FF0000", semester = term, courseNo = calculus.courseNo, signIn = signIn)

        assertTrue(overrides.queueAsOldest(PendingCourseOverrides.Change(calculus.moodleIdNumber!!, colorHex = "#123456", semester = term, courseNo = calculus.courseNo), signIn))
        assertTrue(overrides.queueAsOldest(PendingCourseOverrides.Change(physics.moodleIdNumber!!, colorHex = "#654321", semester = term, courseNo = physics.courseNo), signIn))
        prefs.syncCourseColors = true
        overrides.drain(signIn)

        assertEquals(listOf("#FF0000", "#654321"), v4Backend.sent.map { Regex(""""color_hex":"([^"]+)"""").find(it.body.orEmpty())?.groupValues?.get(1) })
        assertTrue(v4Backend.sent.last().body.orEmpty().endsWith(""","hlc":0}"""))
    }

    private companion object {
        // An older build last wrote the queue file then, before this build first ran.
        const val FILE_MS = 1_780_000_000_000L
        const val FIRST_RUN_MS = 1_785_000_000_000L
        const val CLOCK_US = 1_790_000_000_500_000L
    }

    // --- Rules either version keeps, which their /v3 cases pinned, on /v4 ---

    private val notARoute = """{"error":"not_found","message":"No such route."}"""

    @Test
    fun `on v4 a key written twice goes once, as its last change`() = runTest {
        queue(placed(color(calculus, "#FF0000"), calculus), placed(color(physics, "#00FF00"), physics), placed(color(calculus, "#0000FF"), calculus))

        v4Overrides().drain(signIn)

        assertEquals(
            listOf(
                v4Patch("client:1151:MA1010002", """{"color_hex":"#0000FF","hlc":1790000000123000}"""),
                v4Patch("client:1151:PH1010003", """{"color_hex":"#00FF00","hlc":1790000000123000}"""),
            ),
            v4Shapes(),
        )
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v4 a change the server fails waits uncounted and goes at the next retry`() = runTest {
        queue(placed(color(calculus, "#FF0000"), calculus))
        v4Backend.answer(v4PathOf(calculus), 503, """{"error":"internal_error","message":"made up"}""")
        val overrides = v4Overrides()

        overrides.drain(signIn)
        assertEquals(0, waiting().single().attempts)
        overrides.drain(signIn)

        assertEquals(List(2) { v4Patch("client:1151:MA1010002", """{"color_hex":"#FF0000","hlc":1790000000123000}""") }, v4Shapes())
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v4 a change the backend refuses is counted on disk and kept`() = runTest {
        queue(placed(color(calculus, "#FF0000"), calculus))
        v4Backend.answer(v4PathOf(calculus), 422, """{"error":"invalid_request","message":"made up","fields":[]}""")

        v4Overrides().drain(signIn)

        assertEquals(1, v4Backend.sent.size)
        assertEquals(listOf(1), waiting().map { it.attempts })
    }

    @Test
    fun `on v4 a 404 for a course gone from this phone lets its change go at once`() = runTest {
        // Calculus is no longer cached in the term the change was made in.
        cache.saveCourses(listOf(physics), term, testSignIn())
        queue(placed(color(calculus, "#FF0000", semester = term), calculus))
        v4Backend.answer(v4PathOf(calculus), 404, notFound)

        v4Overrides().drain(signIn)

        assertEquals(1, v4Backend.sent.size)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v4 a 404 for a course still on this phone parks its change at once, and a landed course upload sends it`() = runTest {
        cache.saveCourses(listOf(calculus), term, testSignIn())
        queue(placed(color(calculus, "#FF0000", semester = term), calculus))
        v4Backend.answer(v4PathOf(calculus), 404, notFound)
        val overrides = v4Overrides()

        repeat(3) { overrides.drain(signIn) }
        assertEquals("parked at the first 404, and not sent again", 1, v4Backend.sent.size)
        assertEquals(listOf(max), waiting().map { it.attempts })

        Outbox(setOf(overrides)).courseUploadLanded(signIn)
        overrides.drain(signIn)

        assertEquals(List(2) { v4Patch("client:1151:MA1010002", """{"color_hex":"#FF0000","hlc":1790000000123000}""") }, v4Shapes())
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

    @Test
    fun `on v4 a parked change goes once more at the first retry of the next process`() = runTest {
        queue(placed(color(calculus, "#FF0000"), calculus).copy(attempts = max))
        repeat(2) { v4Backend.answer(v4PathOf(calculus), 422, """{"error":"invalid_request","message":"made up","fields":[]}""") }

        val first = v4Overrides()
        repeat(2) { first.drain(signIn) }
        val next = v4Overrides()
        repeat(2) { next.drain(signIn) }

        assertEquals("once per process, refused again and parked", 2, v4Backend.sent.size)
        assertEquals(listOf(max), waiting().map { it.attempts })
    }

    @Test
    fun `on v4 a 404 that names no missing course is counted, and goes again at the next sync`() = runTest {
        // Still on this phone, so the 404 is no word that the course left it.
        cache.saveCourses(listOf(calculus), term, testSignIn())
        queue(placed(color(calculus, "#FF0000"), calculus))
        v4Backend.answer(v4PathOf(calculus), 404, notARoute)
        val overrides = v4Overrides()

        overrides.drain(signIn)
        assertEquals(listOf(1), waiting().map { it.attempts })
        overrides.drain(signIn)

        assertEquals(2, v4Backend.sent.size)
        assertEquals(emptyList<PendingCourseOverride>(), waiting())
    }

}
