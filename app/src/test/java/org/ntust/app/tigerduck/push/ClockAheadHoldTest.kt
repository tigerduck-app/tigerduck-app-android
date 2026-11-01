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
import org.ntust.app.tigerduck.data.CourseIgnoreSender
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.HolidayOverrideSender
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A 409 `clock_ahead` makes a second, unstamped request. It passes the gate the first one passed
 * again: a hold or a switch put on the kind while the first request waited stops the resend, which
 * would otherwise overwrite the server's value before the user chose which copy to keep.
 */
class ClockAheadHoldTest {

    private val backend = ScriptedBackend()
    private lateinit var prefs: AppPreferences
    private lateinit var api: PushApiClient
    private lateinit var cache: DataCache
    private lateinit var overrides: PendingCourseOverrides
    private lateinit var auth: AuthTokenManager

    private val madeAtMs = 1_790_000_000_123L
    private val clockAhead = """{"detail":"clock_ahead"}"""
    private val course = "PATCH /sync/courses/1151CS1/override"

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
        overrides = PendingCourseOverrides(cache, api, prefs, auth)
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

    @Test
    fun `a color held while its request waits stays queued, and the clock_ahead resend does not go`() = runTest {
        val sent = async { overrides.send("1151CS1", colorHex = "#FF0000") }
        val held = backend.hold(course)
        held.arrived.await()
        // The re-enable check opens for colors while the first request is out.
        prefs.pendingConflictCategories = setOf(PendingCourseOverrides.category(color = true))
        held.body = clockAhead
        held.release(409)
        sent.await()

        assertEquals("no second request", 1, backend.received(course).size)
        val row = cache.loadPendingCourseOverrides().single()
        assertEquals("still waiting, not counted as refused", 0, row.attempts)
        assertTrue(overrides.hasPending())

        // The hold lifts: the next sync sends it, and it clears.
        prefs.pendingConflictCategories = emptySet()
        overrides.retry()
        assertEquals(2, backend.received(course).size)
        assertFalse(overrides.hasPending())
    }

    @Test
    fun `a mark whose resend the switch stopped stays waiting, and goes out once the switch is on`() = runTest {
        val endpoint = "PATCH /sync/assignments/7/override"
        val marks = UnsentMarks(cache, api, prefs, auth)
        val sent = async { marks.send("7", "ignored") }
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
        marks.retry()
        assertEquals(2, backend.received(endpoint).size)
        assertEquals("the retry keeps the time it was made", true, "hlc" in backend.received(endpoint).last().body)
        assertEquals(emptyList<Any>(), cache.loadUnsentMarks())
    }

    @Test
    fun `a course ignore whose resend the switch stopped stays waiting, and goes out once the switch is on`() = runTest {
        val endpoint = "PATCH /sync/courses/1141AAA/override"
        val ignores = CourseIgnoreSync(
            dataCache = cache,
            sender = CourseIgnoreSender(api::patchCourseIgnore),
            effects = CourseIgnoreEffects {},
            sends = { CourseIgnoreSync.syncsCourseIgnores(prefs) },
            clock = System::currentTimeMillis,
        )
        val sent = async { ignores.setIgnored("1141", "AAA", "1141AAA", null, ignore = true) }
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
        ignores.retryPending()
        assertEquals(2, backend.received(endpoint).size)
        assertEquals(emptyList<Any>(), cache.loadPendingCourseIgnores())
    }

    @Test
    fun `a holiday choice whose resend TigerSync stopped stays waiting, and goes out once it is on`() = runTest {
        val endpoint = "PUT /sync/holiday-overrides/3"
        val holidays = PendingHolidayOverrides(
            prefs, HolidayOverrideSender(api::putHolidayOverride), { prefs.cloudSyncEnabled },
            { auth.signInGeneration }, auth::runIfSignedIn,
        )
        prefs.holidayNotifyOverrides = setOf(3)
        val sent = async { holidays.send(3) }
        val held = backend.hold(endpoint)
        held.arrived.await()
        prefs.cloudSyncEnabled = false
        held.body = clockAhead
        held.release(409)
        sent.await()

        assertEquals("no second request", 1, backend.received(endpoint).size)
        assertEquals(setOf(3), holidays.waitingIds)

        prefs.cloudSyncEnabled = true
        holidays.retry()
        assertEquals(2, backend.received(endpoint).size)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a course override whose hold goes on mid-request reports not sent`() = runTest {
        var allowed = true
        val sent = heldThenAhead(course, { allowed = false }) {
            api.patchCourseOverride("1151CS1", colorHex = "#FF0000", allowed = { allowed }, madeAtMs = madeAtMs)
        }

        assertFalse(sent)
        assertEquals(1, backend.received(course).size)
    }

    @Test
    fun `a course color switched off mid-request is not resent`() = runTest {
        val sent = heldThenAhead(course, { prefs.syncCourseColors = false }) {
            api.patchCourseOverride("1151CS1", colorHex = "#FF0000", madeAtMs = madeAtMs)
        }

        assertFalse(sent)
        assertEquals(1, backend.received(course).size)
    }

    @Test
    fun `a course ignore switched off mid-request is not resent`() = runTest {
        val sent = heldThenAhead(course, { prefs.syncAssignments = false }) {
            api.patchCourseIgnore("1151CS1", ignore = true, moodleCourseId = null, madeAtMs = madeAtMs)
        }

        assertEquals(null, sent)
        assertEquals(1, backend.received(course).size)
    }

    @Test
    fun `an assignment mark switched off mid-request is not resent`() = runTest {
        val endpoint = "PATCH /sync/assignments/7/override"
        heldThenAhead(endpoint, { prefs.syncAssignments = false }) {
            api.patchAssignmentOverride(7, "ignored", madeAtMs = madeAtMs)
        }

        assertEquals(1, backend.received(endpoint).size)
    }

    @Test
    fun `a holiday choice sent while TigerSync goes off mid-request is not resent`() = runTest {
        val endpoint = "PUT /sync/holiday-overrides/3"
        val sent = heldThenAhead(endpoint, { prefs.cloudSyncEnabled = false }) {
            api.putHolidayOverride(3, notify = true, madeAtMs = madeAtMs)
        }

        assertFalse(sent)
        assertEquals(1, backend.received(endpoint).size)
    }
}
