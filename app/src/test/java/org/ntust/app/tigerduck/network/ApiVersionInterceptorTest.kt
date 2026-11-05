package org.ntust.app.tigerduck.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.push.syncFixture

/**
 * Which 410s from our backend say the app is too old ([ApiVersionGate]): `api_retired`,
 * `app_version_retired`, or none that names a code. The change feed's `sync_revision_expired` is
 * not one of them: it asks for a full sync, which the app makes.
 */
class ApiVersionInterceptorTest {

    @Before fun reset() = ApiVersionGate.resetForTesting()

    @After fun tearDown() = ApiVersionGate.resetForTesting()

    /** Sends a GET to [url] and has it answered [code] with [body], read in full as a caller would. */
    private fun answer(url: String, code: Int, body: String): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(ApiVersionInterceptor { BACKEND })
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Gone")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
    }

    @Test
    fun `the change feed's expired revision is not a retired API`() {
        val body = """{"error": "sync_revision_expired", "min_available_revision": 40, "current_revision": 52, "full_sync_required": true}"""

        val read = answer("$BACKEND/sync?since_revision=12", 410, body)

        assertFalse(ApiVersionGate.isRetired.value)
        // The feed's caller still reads the body the check looked at.
        assertEquals(body, read)
    }

    @Test
    fun `a retired API version latches the gate`() {
        answer("$BACKEND/sync?since_revision=12", 410, """{"detail": "This API version has been retired.", "current_api": "/v3"}""")

        assertTrue(ApiVersionGate.isRetired.value)
    }

    @Test
    fun `a 410 whose body is not JSON still latches the gate`() {
        answer("$BACKEND/bulletins", 410, "<html>Gone</html>")

        assertTrue(ApiVersionGate.isRetired.value)
    }

    @Test
    fun `a v4 envelope that retires the API or the app version latches the gate`() {
        for (code in listOf("api_retired", "app_version_retired")) {
            ApiVersionGate.resetForTesting()

            answer("$BACKEND/sync/full", 410, """{"error": "$code", "message": "Update the app.", "current_api": "/v4"}""")

            assertTrue(code, ApiVersionGate.isRetired.value)
        }
    }

    @Test
    fun `the v3 410 the backend sends once v3 retires latches the gate`() {
        answer("$BACKEND/sync/full", 410, """{"detail": "This API version has been retired.", "current_api": "/v4", "error": "api_retired"}""")

        assertTrue(ApiVersionGate.isRetired.value)
    }

    @Test
    fun `a 410 with no body latches the gate`() {
        answer("$BACKEND/sync/full", 410, "")

        assertTrue(ApiVersionGate.isRetired.value)
    }

    @Test
    fun `no other status latches the gate, the v4 feed's 409 included`() {
        answer("$BACKEND/sync?since_revision=12", 409, """{"error": "sync_revision_expired", "current_revision": 52}""")
        answer("$BACKEND/sync/full", 404, """{"detail": "api_retired"}""")
        answer("$BACKEND/sync/full", 500, "Internal Server Error")

        assertFalse(ApiVersionGate.isRetired.value)
    }

    /** The one 410 that latched before and no longer does: none the backend sends today. */
    @Test
    fun `a 410 that names a code it does not know leaves the gate alone, in either version`() {
        answer("$BACKEND/bulletin-subscriptions/7", 410, """{"error": "subscription_gone", "message": "Gone."}""")
        answer("$BACKEND/bulletin-subscriptions/7", 410, """{"detail": "subscription_gone"}""")

        assertFalse(ApiVersionGate.isRetired.value)
    }

    @Test
    fun `the v4 feed's expired revision is not a retired API either`() {
        answer("$BACKEND/sync?since_revision=12", 409, syncFixture("v4/feed-expired.json"))
        answer("$BACKEND/sync?since_revision=12", 410, syncFixture("v4/feed-expired.json"))

        assertFalse(ApiVersionGate.isRetired.value)
    }

    @Test
    fun `a 410 from another host leaves the gate alone`() {
        answer("https://moodle.example.test/page", 410, "{}")

        assertFalse(ApiVersionGate.isRetired.value)
    }

    private companion object {
        const val BACKEND = "https://api.example.test/v3"
    }
}
