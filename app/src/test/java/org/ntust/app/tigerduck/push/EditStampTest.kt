package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * A course override, an assignment mark and a holiday choice go up with the time the edit was
 * made as `hlc` (iOS 2.9.2, tigerduck-app#922), so one that waited in a queue is no newer than
 * the edit. A 409 `clock_ahead` goes again once, stamped again ([V4WritesTest]); nothing else is
 * sent again.
 */
class EditStampTest {

    private val backend = ScriptedBackend(under = "/v4")
    private val signIn = testSignIn()
    private lateinit var api: PushApiClient

    private val madeAtMs = 1_790_000_000_123L
    private val hlc = 1_790_000_000_123_000L
    private val clockAhead = syncFixture("v4/error-clock-ahead.json")
    private val key = CourseRef(moodleId = null, courseKey = "client:1151:CS1")
    private val course = "PATCH /sync/courses/client:1151:CS1/override"

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
    fun `a course color carries the time it was made as hlc`() = runTest {
        api.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAtMs = madeAtMs)

        val body = bodies(course).single()
        assertEquals(hlc, body.getLong("hlc"))
        assertEquals("#FF0000", body.getString("color_hex"))
    }

    @Test
    fun `a course name carries the time it was made as hlc`() = runTest {
        api.patchCourseOverrideAnswered(key, signIn, customName = "Algo", locale = "en", madeAtMs = madeAtMs)

        assertEquals(hlc, bodies(course).single().getLong("hlc"))
    }

    @Test
    fun `a course ignore carries the time it was made as hlc`() = runTest {
        api.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = 7, signIn = signIn, madeAtMs = madeAtMs)

        val body = bodies(course).single()
        assertEquals(hlc, body.getLong("hlc"))
        assertTrue(body.getBoolean("ignore_assignments"))
    }

    @Test
    fun `an assignment mark carries the time it was made as hlc`() = runTest {
        api.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs)

        val body = bodies("PATCH /sync/assignments/7/override").single()
        assertEquals(hlc, body.getLong("hlc"))
        assertEquals("ignored", body.getString("local_status"))
    }

    @Test
    fun `a holiday choice carries the time it was made as hlc`() = runTest {
        assertTrue(api.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = madeAtMs).sent)

        val body = bodies("PUT /sync/holiday-overrides/3").single()
        assertEquals(hlc, body.getLong("hlc"))
        assertTrue(body.getBoolean("notify"))
    }

    @Test
    fun `a clock_ahead reply to the resend is not resent again, and other refusals are not resent`() = runTest {
        backend.answer(course, 409, clockAhead)
        backend.answer(course, 409, clockAhead)
        val ahead = runCatching { api.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAtMs = madeAtMs) }
            .exceptionOrNull()
        assertEquals(409, ahead?.backendError()?.status)
        assertEquals(2, backend.received(course).size)

        backend.answer("PATCH /sync/assignments/7/override", 404)
        val missing = runCatching { api.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs) }
            .exceptionOrNull()
        assertEquals(404, missing?.backendError()?.status)
        assertEquals(1, backend.received("PATCH /sync/assignments/7/override").size)
    }

    @Test
    fun `a 409 that is not clock_ahead is not resent, for every kind`() = runTest {
        val other = """{"error":"conflict","message":"made up"}"""
        backend.answer(course, 409, other)
        backend.answer(course, 409, other)
        backend.answer("PATCH /sync/assignments/7/override", 409, other)
        backend.answer("PUT /sync/holiday-overrides/3", 409, other)

        val failures = listOf(
            runCatching { api.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAtMs = madeAtMs) },
            runCatching { api.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = madeAtMs) },
            runCatching { api.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = madeAtMs) },
            runCatching { api.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = madeAtMs) },
        ).map { it.exceptionOrNull()?.backendError() }

        assertEquals(List(4) { BackendError(409, "conflict") }, failures)
        assertEquals(2, backend.received(course).size)
        assertEquals(1, backend.received("PATCH /sync/assignments/7/override").size)
        assertEquals(1, backend.received("PUT /sync/holiday-overrides/3").size)
        assertTrue((backend.received(course) + backend.received("PUT /sync/holiday-overrides/3")).all { "hlc" in it.body })
    }

    @Test
    fun `clock_ahead is read from either version's body, and from a 409 only`() {
        fun answer(code: Int, body: String) = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://127.0.0.1:9/v4/sync/assignments/7/override").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

        assertTrue(isClockAhead(answer(409, clockAhead)))
        assertTrue(isClockAhead(answer(409, """{"detail":"clock_ahead"}""")))
        assertFalse(isClockAhead(answer(409, """{"error":"settings_conflict","detail":"clock_ahead"}""")))
        assertFalse(isClockAhead(answer(422, syncFixture("v4/error-clock-ahead.json"))))
        assertFalse(isClockAhead(answer(409, "clock_ahead")))
        // Peeked: the caller still reads the whole body.
        val read = answer(409, clockAhead)
        isClockAhead(read)
        assertEquals(clockAhead, read.body.string())
    }
}
