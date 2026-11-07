package org.ntust.app.tigerduck.coursecatalog

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.clubs.ETaggedBody
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `GET course-catalog/{semester}?language=`: the session and the stored ETag go with it, a 304
 * keeps the stored copy, a 200 brings a new one with its ETag, and a 404 means no catalog for the
 * term. As the iPhone's `CourseCatalogClientTests`; a fake backend answers, nothing leaves the JVM.
 */
class CourseCatalogClientTest {

    private class Answer(val code: Int, val body: String = "", val etag: String? = null)

    private val sent = CopyOnWriteArrayList<Request>()
    private val answers = ConcurrentLinkedQueue<Answer>()
    private val backend = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            sent += request
            val answer = answers.poll() ?: Answer(500)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(answer.code)
                .message("")
                .apply { answer.etag?.let { header("ETag", it) } }
                .body(answer.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val client: CourseCatalogClient = run {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the fake one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-catalog-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        CourseCatalogClient(AuthTokenManager(tokens, backend, prefs, "device-1"), prefs, backend)
    }

    private fun catalogRequests() = sent.filter { it.url.encodedPath.endsWith("/course-catalog/1151") }

    @Test
    fun `a fetch sends the session and the stored ETag, and a 304 keeps the stored copy`() = runTest {
        answers += Answer(304)

        val answer = client.fetch("1151", "en", "W/\"a\"", testSignIn())

        assertEquals(CourseCatalogAnswer.NotModified, answer)
        val request = catalogRequests().single()
        assertEquals("GET", request.method)
        assertEquals("en", request.url.queryParameter("language"))
        assertEquals("Bearer access", request.header("Authorization"))
        assertEquals("W/\"a\"", request.header("If-None-Match"))
    }

    @Test
    fun `a new catalog comes back with its ETag, and a first fetch sends none`() = runTest {
        answers += Answer(200, """{"courses":[]}""", etag = "W/\"b\"")

        val answer = client.fetch("1151", "zh", null, testSignIn())

        assertEquals(CourseCatalogAnswer.Changed(ETaggedBody("""{"courses":[]}""", "W/\"b\"")), answer)
        val request = catalogRequests().single()
        assertEquals("zh", request.url.queryParameter("language"))
        assertNull(request.header("If-None-Match"))
    }

    @Test
    fun `a term the backend has not stored is unavailable`() = runTest {
        answers += Answer(404, """{"error":"course_catalog_not_found","message":"course_catalog_not_found"}""")

        try {
            client.fetch("1151", "zh", null, testSignIn())
            fail("expected the catalog to be unavailable")
        } catch (_: CourseCatalogUnavailableException) {
        }
    }

    @Test
    fun `any other refusal carries its status`() = runTest {
        answers += Answer(503)

        val error = runCatching { client.fetch("1151", "zh", null, testSignIn()) }.exceptionOrNull()

        assertEquals(503, (error as? PushApiException)?.statusCode)
    }

    @Test
    fun `without a session of the sign-in nothing is sent`() = runTest {
        val series = SignInSeries()
        val signIn = series.current
        series.end()

        val error = runCatching { client.fetch("1151", "zh", null, signIn) }.exceptionOrNull()

        assertTrue(error.toString(), error is NotSignedInException)
        assertTrue(sent.isEmpty())
    }
}
