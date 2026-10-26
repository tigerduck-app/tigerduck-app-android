package org.ntust.app.tigerduck.network

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the reminder's status call sends. The probe is handed a reader for the
 * stored token and nothing that renews one, so it cannot sign in or refresh
 * the token (issue #212); these pin that it asks with the stored token alone,
 * or not at all.
 */
class SubmissionStatusProbeTest {

    private var storedToken: String? = "stored-token"
    private val sent = mutableListOf<Request>()

    /** Runs while a call is out, before Moodle answers. */
    private var meanwhile: () -> Unit = {}

    // Answers in place of the network: the interceptor never calls proceed().
    private val probe = SubmissionStatusProbe(
        { storedToken },
        OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request()
            meanwhile()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(
                    """{"lastattempt":{"submission":{"status":"new"}},"warnings":[]}"""
                        .toResponseBody("application/json".toMediaType())
                )
                .build()
        }.build(),
    )

    @Test
    fun `without a stored token it sends nothing and answers null`() = runBlocking {
        storedToken = null
        assertNull(probe.status(20977))
        storedToken = ""
        assertNull(probe.status(20977))

        assertEquals(emptyList<Request>(), sent)
    }

    @Test
    fun `it asks once, with the stored token and no user id`() = runBlocking {
        val status = probe.status(20977)

        assertEquals("new", status?.lastattempt?.submission?.status)
        val request = sent.single()
        assertEquals(false, "stored-token" in request.url.toString())
        assertEquals("mod_assign_get_submission_status", request.url.queryParameter("wsfunction"))
        val form = request.body as FormBody
        assertEquals(
            listOf("wstoken" to "stored-token", "assignid" to "20977"),
            (0 until form.size).map { form.name(it) to form.value(it) },
        )
    }

    @Test
    fun `an answer that outlived the token it was asked with is no answer`() = runBlocking {
        meanwhile = { storedToken = null }
        assertNull("signed out while the call ran", probe.status(20977))

        storedToken = "stored-token"
        meanwhile = { storedToken = "next-account" }
        assertNull("another account signed in meanwhile", probe.status(20977))
    }
}
