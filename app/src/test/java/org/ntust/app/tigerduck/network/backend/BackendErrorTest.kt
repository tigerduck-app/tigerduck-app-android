package org.ntust.app.tigerduck.network.backend

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.syncFixture
import java.io.IOException

/**
 * The backend's error code, read once whichever version answered: `/v4`'s envelope names it in
 * `error` (`docs/api-v4.md` on tigerduck-backend, "Error envelope", fixtures in `sync/v4/`), and
 * `/v3` in `detail` where its route has one. Prose, lists and plain text name none.
 */
class BackendErrorTest {

    private fun response(status: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://api.example.test/v4/sync/full").build())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun codeOf(status: Int, body: String): String? = BackendError.of(response(status, body)).code

    @Test
    fun `every v4 envelope in the contract's table names its code`() {
        val table = JSONObject(syncFixture("v4/error-codes.json"))
        var codes = 0
        for (status in table.keys()) {
            val listed = table.getJSONArray(status)
            for (i in 0 until listed.length()) {
                val code = listed.getString(i)
                val envelope = JSONObject().put("error", code).put("message", "Something, in English.").toString()

                assertEquals(BackendError(status.toInt(), code), BackendError.of(response(status.toInt(), envelope)))
                codes++
            }
        }
        assertEquals("the contract's table lists 41 codes", 41, codes)
    }

    @Test
    fun `a v4 envelope with fields of its own names its code`() {
        val cases = mapOf(
            "v4/error-clock-ahead.json" to (409 to "clock_ahead"),
            "v4/error-api-retired.json" to (410 to "api_retired"),
            "v4/error-app-version-retired.json" to (410 to "app_version_retired"),
            "v4/feed-expired.json" to (409 to "sync_revision_expired"),
            "v4/error-settings-conflict.json" to (409 to "settings_conflict"),
            "v4/error-invalid-request.json" to (422 to "invalid_request"),
            "v4/error-session-user-mismatch.json" to (401 to "session_user_mismatch"),
            "v4/error-not-found.json" to (404 to "not_found"),
            "v4/error-internal.json" to (500 to "internal_error"),
        )
        for ((fixture, expected) in cases) {
            val (status, code) = expected

            assertEquals(fixture, code, codeOf(status, syncFixture(fixture)))
        }
    }

    @Test
    fun `each v3 body names its code where it has one`() {
        assertEquals("session_revoked", codeOf(401, """{"detail":"session_revoked"}"""))
        assertEquals("invalid_access_token", codeOf(401, """{"detail":"invalid_access_token"}"""))
        assertEquals("clock_ahead", codeOf(409, """{"detail":"clock_ahead"}"""))
        assertEquals("add_not_in_courses", codeOf(422, """{"detail":"add_not_in_courses"}"""))
        assertEquals("course_not_found", codeOf(404, """{"detail":"course_not_found"}"""))
        assertEquals("device_id_required", codeOf(400, """{"detail":"device_id_required"}"""))
        assertEquals("moodle_unreachable", codeOf(503, """{"detail":"moodle_unreachable"}"""))
        // The feed's pruned revision and a settings conflict say theirs in `error` on /v3 already.
        assertEquals(
            "sync_revision_expired",
            codeOf(410, """{"error":"sync_revision_expired","min_available_revision":40,"current_revision":52,"full_sync_required":true}"""),
        )
        assertEquals("settings_conflict", codeOf(409, """{"error":"settings_conflict","namespace":"notifications","server":{}}"""))
        // The v3 410 once /v3 retires keeps its prose detail and adds the code.
        assertEquals(
            "api_retired",
            codeOf(410, """{"detail":"This API version has been retired.","current_api":"/v4","error":"api_retired"}"""),
        )
    }

    @Test
    fun `prose, a list, plain text or no body names no code`() {
        assertNull(codeOf(404, """{"detail":"device not found"}"""))
        assertNull(codeOf(429, """{"detail":"too many refresh attempts, try again later"}"""))
        assertNull(codeOf(410, """{"detail":"This API version has been retired. Update the app to the latest version.","current_api":"/v3"}"""))
        assertNull(codeOf(422, """{"detail":[{"type":"missing","loc":["query","since_revision"],"msg":"Field required"}]}"""))
        assertNull(codeOf(500, "Internal Server Error"))
        assertNull(codeOf(502, "<html><body>Bad Gateway</body></html>"))
        assertNull(codeOf(410, ""))
        assertNull(codeOf(410, """["api_retired"]"""))
        // A proxy's own JSON is prose too, so it retires nothing it does not say.
        assertNull(codeOf(410, """{"error":"Gone"}"""))
    }

    @Test
    fun `error goes before detail, and a null or prose error leaves detail to say it`() {
        assertEquals("clock_ahead", codeOf(409, """{"error":"clock_ahead","detail":"login_conflict_retry"}"""))
        assertEquals("clock_ahead", codeOf(409, """{"error":null,"detail":"clock_ahead"}"""))
        assertEquals("clock_ahead", codeOf(409, """{"error":"Conflict","detail":"clock_ahead"}"""))
        assertEquals("clock_ahead", codeOf(409, """{"error":42,"detail":"clock_ahead"}"""))
    }

    @Test
    fun `the body is peeked, so the caller still reads all of it`() {
        val body = syncFixture("v4/feed-expired.json")
        val answer = response(409, body)

        assertEquals("sync_revision_expired", BackendError.of(answer).code)
        assertEquals(body, answer.body.string())
    }

    @Test
    fun `a body longer than an error's names no code`() {
        val long = """{"error":"api_retired","padding":"${"x".repeat(5_000)}"}"""

        assertNull(codeOf(410, long))
    }

    @Test
    fun `what each code means, by status`() {
        assertTrue(BackendError(409, "clock_ahead").isClockAhead)
        assertFalse(BackendError(422, "clock_ahead").isClockAhead)
        assertFalse(BackendError(409, "settings_conflict").isClockAhead)

        assertTrue(BackendError(409, "sync_revision_expired").isRevisionExpired)
        assertFalse("a 410 is for updating the app", BackendError(410, "sync_revision_expired").isRevisionExpired)
        assertFalse(BackendError(409, "clock_ahead").isRevisionExpired)
        assertFalse(BackendError(409, null).isRevisionExpired)
        assertFalse(BackendError(500, "sync_revision_expired").isRevisionExpired)

        assertTrue(BackendError(410, "api_retired").retiresApp)
        assertTrue(BackendError(410, "app_version_retired").retiresApp)
        assertTrue(BackendError(410, null).retiresApp)
        assertFalse(BackendError(410, "sync_revision_expired").retiresApp)
        assertFalse(BackendError(410, "subscription_gone").retiresApp)
        assertFalse(BackendError(404, null).retiresApp)

        assertTrue(BackendError(401, "session_revoked").refusesSession)
        assertTrue(BackendError(401, "session_user_mismatch").refusesSession)
        assertTrue(BackendError(401, null).refusesSession)
        assertFalse(BackendError(403, "session_revoked").refusesSession)
    }

    @Test
    fun `a failure carries the backend's refusal only as data`() {
        assertEquals(BackendError(401, "session_revoked"), BackendException(BackendError(401, "session_revoked"), "x").backendError())
        assertEquals(BackendError(503, null), PushApiException("sync/full failed: HTTP 503", 503).backendError())
        assertNull(PushApiException("register failed: HTTP 401 {\"detail\":\"session_revoked\"}").backendError())
        assertNull(IOException("HTTP 401").backendError())
        assertTrue("a BackendException is an IO failure, as the requests' own are", BackendException(BackendError(500, null), "x") is IOException)
    }
}
