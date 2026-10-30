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

/**
 * Which 410s from our backend say the app is too old ([ApiVersionGate]). The change feed's
 * `sync_revision_expired` is not one of them: it asks for a full sync, which the app makes.
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
    fun `a 410 from another host leaves the gate alone`() {
        answer("https://moodle.example.test/page", 410, "{}")

        assertFalse(ApiVersionGate.isRetired.value)
    }

    private companion object {
        const val BACKEND = "https://api.example.test/v3"
    }
}
