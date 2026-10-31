package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A course override, an assignment mark and a holiday choice go up with the time the edit was
 * made as `hlc` (iOS 2.9.2, tigerduck-app#922), so one that waited in a queue is no newer than
 * the edit. A 409 `clock_ahead` goes again without it, as the course upload's adds do.
 */
class EditStampTest {

    private val backend = ScriptedBackend()
    private lateinit var api: PushApiClient

    private val madeAtMs = 1_790_000_000_123L
    private val hlc = 1_790_000_000_123_000L
    private val clockAhead = """{"detail":"clock_ahead"}"""

    @Before
    fun setUp() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-edit-stamp-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
    }

    private fun bodies(endpoint: String) = backend.received(endpoint).map { JSONObject(it.body) }

    @Test
    fun `a course colour carries the time it was made as hlc`() = runTest {
        api.patchCourseOverride("1151CS1", colorHex = "#FF0000", madeAtMs = madeAtMs)

        val body = bodies("PATCH /sync/courses/1151CS1/override").single()
        assertEquals(hlc, body.getLong("hlc"))
        assertEquals("#FF0000", body.getString("color_hex"))
    }

    @Test
    fun `a course name carries the time it was made as hlc`() = runTest {
        api.patchCourseOverride("1151CS1", customName = "Algo", locale = "en", madeAtMs = madeAtMs)

        assertEquals(hlc, bodies("PATCH /sync/courses/1151CS1/override").single().getLong("hlc"))
    }

    @Test
    fun `a course ignore carries the time it was made as hlc`() = runTest {
        api.patchCourseIgnore("1151CS1", ignore = true, moodleCourseId = 7, madeAtMs = madeAtMs)

        val body = bodies("PATCH /sync/courses/1151CS1/override").single()
        assertEquals(hlc, body.getLong("hlc"))
        assertTrue(body.getBoolean("ignore_assignments"))
    }

    @Test
    fun `an assignment mark carries the time it was made as hlc`() = runTest {
        api.patchAssignmentOverride(7, "ignored", madeAtMs = madeAtMs)

        val body = bodies("PATCH /sync/assignments/7/override").single()
        assertEquals(hlc, body.getLong("hlc"))
        assertEquals("ignored", body.getString("local_status"))
    }

    @Test
    fun `a holiday choice carries the time it was made as hlc`() = runTest {
        assertTrue(api.putHolidayOverride(3, notify = true, madeAtMs = madeAtMs))

        val body = bodies("PUT /sync/holiday-overrides/3").single()
        assertEquals(hlc, body.getLong("hlc"))
        assertTrue(body.getBoolean("notify"))
    }

    @Test
    fun `an edit with no time goes without hlc, as before`() = runTest {
        api.patchCourseOverride("1151CS1", colorHex = "#FF0000")
        api.patchCourseIgnore("1151CS1", ignore = true, moodleCourseId = null)
        api.patchAssignmentOverride(7, "ignored")
        api.putHolidayOverride(3, notify = true)

        val all = bodies("PATCH /sync/courses/1151CS1/override") + bodies("PATCH /sync/assignments/7/override") +
            bodies("PUT /sync/holiday-overrides/3")
        assertEquals(4, all.size)
        assertTrue(all.none { it.has("hlc") })
    }

    @Test
    fun `a clock_ahead reply is resent once without hlc, for every kind`() = runTest {
        val course = "PATCH /sync/courses/1151CS1/override"
        // The colour's 409, its resend, then the ignore's 409.
        backend.answer(course, 409, clockAhead)
        backend.answer(course, 200, """{"color_hex":"#FF0000"}""")
        backend.answer(course, 409, clockAhead)
        backend.answer("PATCH /sync/assignments/7/override", 409, clockAhead)
        backend.answer("PUT /sync/holiday-overrides/3", 409, clockAhead)

        assertTrue(api.patchCourseOverride("1151CS1", colorHex = "#FF0000", madeAtMs = madeAtMs))
        api.patchCourseIgnore("1151CS1", ignore = true, moodleCourseId = null, madeAtMs = madeAtMs)
        api.patchAssignmentOverride(7, "ignored", madeAtMs = madeAtMs)
        assertTrue(api.putHolidayOverride(3, notify = true, madeAtMs = madeAtMs))

        assertEquals(listOf(true, false, true, false), bodies(course).map { it.has("hlc") })
        assertEquals(listOf(true, false), bodies("PATCH /sync/assignments/7/override").map { it.has("hlc") })
        assertEquals(listOf(true, false), bodies("PUT /sync/holiday-overrides/3").map { it.has("hlc") })
    }

    @Test
    fun `a clock_ahead reply to the resend is not resent again, and other refusals are not resent`() = runTest {
        val course = "PATCH /sync/courses/1151CS1/override"
        backend.answer(course, 409, clockAhead)
        backend.answer(course, 409, clockAhead)
        val ahead = runCatching { api.patchCourseOverride("1151CS1", colorHex = "#FF0000", madeAtMs = madeAtMs) }
            .exceptionOrNull() as PushApiException
        assertEquals(409, ahead.statusCode)
        assertEquals(2, backend.received(course).size)

        backend.answer("PATCH /sync/assignments/7/override", 404)
        val missing = runCatching { api.patchAssignmentOverride(7, "ignored", madeAtMs = madeAtMs) }
            .exceptionOrNull() as PushApiException
        assertEquals(404, missing.statusCode)
        assertEquals(1, backend.received("PATCH /sync/assignments/7/override").size)
        assertFalse(backend.received("PATCH /sync/assignments/7/override").isEmpty())
    }
}
