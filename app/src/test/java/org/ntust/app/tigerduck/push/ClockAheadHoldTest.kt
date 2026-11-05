package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.CourseIgnoreEffects
import org.ntust.app.tigerduck.data.CourseIgnoreWriter
import org.ntust.app.tigerduck.data.CallersScope
import org.ntust.app.tigerduck.data.IgnoreOutbox
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * A 409 `clock_ahead` makes a second request, stamped again from the refusal's clock. It passes
 * the gate the first one passed again: a hold or a switch put on the kind while the first request
 * waited stops the resend, which would otherwise overwrite the server's value before the user chose
 * which copy to keep, and the edit waits, uncounted, for the gate to open.
 */
class ClockAheadHoldTest {

    private val backend = ScriptedBackend(under = "/v4")
    private lateinit var prefs: AppPreferences
    private lateinit var api: PushApiClient
    private lateinit var cache: DataCache
    private lateinit var overrides: PendingCourseOverrides
    private lateinit var auth: AuthTokenManager

    private val madeAtMs = 1_790_000_000_123L
    private val clockAhead = syncFixture("v4/error-clock-ahead.json")
    private val course = "PATCH /sync/courses/client:1151:CS1/override"
    private val key = CourseRef(courseKey = "client:1151:CS1")

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-clock-ahead-hold-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        auth = AuthTokenManager(tokens, backend.client, prefs, "device-1")
        api = PushApiClient(backend.client, prefs, auth)
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        overrides = PendingCourseOverrides(cache, api, prefs)
    }

    /**
     * Starts [send], holds its first request on [endpoint] until it is on the wire, puts the hold
     * on with [hold], and answers that request `clock_ahead`; returns what [send] returns.
     */
    private suspend fun <T> CoroutineScope.heldThenAhead(endpoint: String, hold: () -> Unit, send: suspend () -> T): T {
        val held = backend.hold(endpoint)
        val result = async { send() }
        held.arrived.await()
        hold()
        held.body = clockAhead
        held.release(409)
        return result.await()
    }

    private val signIn get() = auth.session.signIns.current

    @Test
    fun `a color held while its request waits stays queued, and the clock_ahead resend does not go`() = runTest {
        val term = cache.currentSemesterCode()
        val endpoint = "PATCH /sync/courses/client:$term:CS1/override"
        cache.saveCourses(listOf(Course(courseNo = "CS1", courseName = "CS1", moodleIdNumber = "${term}CS1")), term, signIn)
        val sent = async { overrides.send("${term}CS1", colorHex = "#FF0000", signIn = signIn) }
        val held = backend.hold(endpoint)
        held.arrived.await()
        // The re-enable check opens for colors while the first request is out.
        prefs.school.pendingConflictCategories.set(setOf(PendingCourseOverrides.category(color = true)), testSignIn())
        held.body = clockAhead
        held.release(409)
        sent.await()

        assertEquals("no second request", 1, backend.received(endpoint).size)
        val row = cache.loadPendingCourseOverrides().single()
        assertEquals("still waiting, not counted as refused", 0, row.attempts)
        assertTrue(overrides.hasPending())

        // The hold lifts: the next sync sends it, and it clears.
        prefs.school.pendingConflictCategories.set(emptySet(), testSignIn())
        overrides.drain(signIn)
        assertEquals(2, backend.received(endpoint).size)
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a mark whose resend the switch stopped stays waiting, and goes out once the switch is on`() = runTest {
        val endpoint = "PATCH /sync/assignments/7/override"
        val marks = UnsentMarks(cache, api, prefs)
        val sent = async { marks.send("7", "ignored", signIn) }
        val held = backend.hold(endpoint)
        held.arrived.await()
        prefs.syncAssignments = false
        held.body = clockAhead
        held.release(409)
        sent.await()

        assertEquals("no second request", 1, backend.received(endpoint).size)
        val row = cache.loadUnsentMarks().single()
        assertEquals("still waiting, not counted as refused", 0, row.attempts)

        prefs.syncAssignments = true
        marks.drain(signIn)
        assertEquals(2, backend.received(endpoint).size)
        assertEquals("the retry keeps the time it was made", true, "hlc" in backend.received(endpoint).last().body)
        assertEquals(emptyList<Any>(), cache.loadUnsentMarks())
    }

    @Test
    fun `a course ignore whose resend the switch stopped stays waiting, and goes out once the switch is on`() = runTest {
        val endpoint = "PATCH /sync/courses/client:1141:AAA/override"
        val ignores = CourseIgnoreSync(
            dataCache = cache,
            writer = CourseIgnoreWriter(api::patchCourseIgnoreAnswered),
            effects = CourseIgnoreEffects {},
            sends = { CourseIgnoreSync.syncsCourseIgnores(prefs) },
            clock = System::currentTimeMillis,
            // As the app wires it: the backend settles each change by its stamp.
            outbox = IgnoreOutbox(System::currentTimeMillis, AckClock(), CallersScope(), null),
        )
        val sent = async { ignores.setIgnored("1141", "AAA", "1141AAA", null, ignore = true, signIn = signIn) }
        val held = backend.hold(endpoint)
        held.arrived.await()
        prefs.syncCourses = false
        held.body = clockAhead
        held.release(409)
        sent.await()

        assertEquals("no second request", 1, backend.received(endpoint).size)
        assertEquals("still waiting, not counted as refused", 0, cache.loadPendingCourseIgnores().single().attempts)

        prefs.syncCourses = true
        backend.answer(endpoint, 200, """{"ignore_assignments":true}""")
        ignores.retryPending(signIn)
        assertEquals(2, backend.received(endpoint).size)
        assertEquals(emptyList<Any>(), cache.loadPendingCourseIgnores())
    }

    @Test
    fun `a holiday choice whose resend TigerSync stopped stays waiting, and goes out once it is on`() = runTest {
        val endpoint = "PUT /sync/holiday-overrides/3"
        val holidays = PendingHolidayOverrides(prefs, api)
        prefs.holidayNotifyOverrides = setOf(3)
        val sent = async { holidays.send(3, signIn) }
        val held = backend.hold(endpoint)
        held.arrived.await()
        prefs.cloudSyncEnabled = false
        held.body = clockAhead
        held.release(409)
        sent.await()

        assertEquals("no second request", 1, backend.received(endpoint).size)
        assertEquals(setOf(3), holidays.waitingIds)

        prefs.cloudSyncEnabled = true
        holidays.drain(signIn)
        assertEquals(2, backend.received(endpoint).size)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a course override whose hold goes on mid-request reports not sent`() = runTest {
        var allowed = true
        val sent = heldThenAhead(course, { allowed = false }) {
            api.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", allowed = { allowed }, madeAtMs = madeAtMs)
        }

        assertFalse(sent.sent)
        assertEquals(1, backend.received(course).size)
    }

    @Test
    fun `a course color switched off mid-request is not resent`() = runTest {
        val sent = heldThenAhead(course, { prefs.syncCourseColors = false }) {
            api.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAtMs = madeAtMs)
        }

        assertFalse(sent.sent)
        assertEquals(1, backend.received(course).size)
    }

    @Test
    fun `a course ignore switched off mid-request is not resent`() = runTest {
        val sent = heldThenAhead(course, { prefs.syncAssignments = false }) {
            api.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = madeAtMs)
        }

        assertFalse(sent.sent)
        assertEquals(1, backend.received(course).size)
    }

    @Test
    fun `an assignment mark switched off mid-request is not resent`() = runTest {
        val endpoint = "PATCH /sync/assignments/7/override"
        val sent = heldThenAhead(endpoint, { prefs.syncAssignments = false }) {
            api.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs)
        }

        assertFalse(sent.sent)
        assertEquals(1, backend.received(endpoint).size)
    }

    @Test
    fun `a holiday choice sent while TigerSync goes off mid-request is not resent`() = runTest {
        val endpoint = "PUT /sync/holiday-overrides/3"
        val sent = heldThenAhead(endpoint, { prefs.cloudSyncEnabled = false }) {
            api.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = madeAtMs)
        }

        assertFalse(sent.sent)
        assertEquals(1, backend.received(endpoint).size)
    }
}
