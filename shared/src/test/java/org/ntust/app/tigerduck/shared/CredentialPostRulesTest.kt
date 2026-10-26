package org.ntust.app.tigerduck.shared

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [CredentialPost] and [executeCredentialPost], against a stand-in that answers as an application
 * interceptor, ahead of OkHttp's own redirect and retry handling. As on the iPhone
 * (`CredentialPostTests`). Hand-made answers throughout.
 */
class CredentialPostRulesTest {

    /** Every request the stand-in got, as "METHOD url", with " +body" when it carried one. */
    private val requests = CopyOnWriteArrayList<String>()

    private fun client(answer: (Interceptor.Chain) -> Response) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url}" + if (request.body != null) " +body" else ""
            answer(chain)
        }
        .build()

    private fun post(url: String = SIGN_IN_URL) = Request.Builder()
        .url(url)
        .post(FormBody.Builder().add("id_token", "made-up-id-token").build())
        .build()

    @Test
    fun `a form passing the sign-in on posts only to the school's hosts over HTTPS`() {
        val school = listOf(
            "https://courseselection.ntust.edu.tw/signin-oidc",
            "https://stuinfosys.ntust.edu.tw/Shibboleth.sso/SAML2/POST",
            "HTTPS://Moodle2.NTUST.EDU.TW/auth/oidc/",
            "https://ntust.edu.tw/",
        )
        for (url in school) assertTrue(url, CredentialPost.isSchoolTarget(url.toHttpUrl()))

        val elsewhere = listOf(
            "http://courseselection.ntust.edu.tw/signin-oidc",
            "https://elsewhere.example/signin-oidc",
            "https://courseselection.ntust.edu.tw.elsewhere.example/signin-oidc",
            "https://courseselection.ntust.edu.tw@elsewhere.example/signin-oidc",
            "https://elsewherentust.edu.tw/signin-oidc",
            "https://courseselection.ntust.edu.tw./signin-oidc",
        )
        for (url in elsewhere) assertFalse(url, CredentialPost.isSchoolTarget(url.toHttpUrl()))
    }

    @Test
    fun `a 307 or a 308 is not followed, to the same host either`() {
        for (code in listOf(307, 308)) {
            requests.clear()
            val school = client { chain -> redirect(chain.request(), code, "/account/login?node=2") }

            assertNull("$code", school.executeCredentialPost(post()))
            assertEquals("$code", listOf("POST $SIGN_IN_URL +body"), requests)
        }
    }

    @Test
    fun `a 307 is followed with the body only where the caller allows`() {
        val school = client { chain ->
            val request = chain.request()
            if (request.url.encodedQuery == null) redirect(request, 307, "/account/login?node=2") else page(request)
        }

        school.executeCredentialPost(post(), formMayGoOnTo = { it.host == "ssoam2.ntust.edu.tw" })!!.close()

        assertEquals(listOf("POST $SIGN_IN_URL +body", "POST $SIGN_IN_URL?node=2 +body"), requests)
    }

    @Test
    fun `a 301, 302 or 303 is followed over HTTPS as a GET without the body`() {
        for (code in listOf(301, 302, 303)) {
            requests.clear()
            val school = client { chain ->
                val request = chain.request()
                if (request.method == "POST") redirect(request, code, CALLBACK_URL) else page(request)
            }

            school.executeCredentialPost(post())!!.use { assertEquals("$code", CALLBACK_URL, it.request.url.toString()) }
            assertEquals("$code", listOf("POST $SIGN_IN_URL +body", "GET $CALLBACK_URL"), requests)
        }
    }

    @Test
    fun `a redirect to plain HTTP is not followed`() {
        for (code in listOf(301, 302, 303)) {
            requests.clear()
            val school = client { chain -> redirect(chain.request(), code, "http://ssoam2.ntust.edu.tw/connect/authorize") }

            assertNull("$code", school.executeCredentialPost(post()))
            assertEquals("$code", listOf("POST $SIGN_IN_URL +body"), requests)
        }
    }

    @Test
    fun `the POST is sent again after a dropped connection only when the caller asks`() {
        val retries = mutableListOf<Boolean>()
        val school = client { chain ->
            retries += chain.retryOnConnectionFailure
            page(chain.request())
        }

        school.executeCredentialPost(post())!!.close()
        school.executeCredentialPost(post(), retryOnConnectionFailure = true)!!.close()

        assertEquals(listOf(false, true), retries)
    }

    @Test
    fun `a 503 asking for the POST again at once does not get it unless the caller retries`() {
        // OkHttp sends a request again after a 503 with Retry-After: 0 whatever the client's retry
        // setting, below the interceptors, so this one is a real server on the loopback address.
        for (retry in listOf(false, true)) {
            val posts = AtomicInteger()
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                thread(isDaemon = true) {
                    while (true) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        socket.use { answerUnavailable(it, posts) }
                    }
                }
                val url = HttpUrl.Builder().scheme("http").host(server.inetAddress.hostAddress).port(server.localPort)
                    .encodedPath("/account/login").build().toString()

                OkHttpClient.Builder().build().executeCredentialPost(post(url), retryOnConnectionFailure = retry)!!.use {
                    assertEquals(503, it.code)
                }
            }
            assertEquals("retry $retry", if (retry) 2 else 1, posts.get())
        }
    }

    /** Reads one request off [socket] and answers it 503, to be asked again at once. */
    private fun answerUnavailable(socket: Socket, posts: AtomicInteger) {
        val input = socket.getInputStream().buffered()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            head.append(byte.toChar())
        }
        val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { input.read() }
        if (head.startsWith("POST")) posts.incrementAndGet()
        socket.getOutputStream().apply {
            write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
    }

    @Test
    fun `each call goes through the caller's run, the GET after a redirect too`() {
        val school = client { chain ->
            val request = chain.request()
            if (request.method == "POST") redirect(request, 302, CALLBACK_URL) else page(request)
        }
        val ran = mutableListOf<String>()

        school.executeCredentialPost(post()) { call ->
            ran += call.request().method
            call.execute()
        }!!.close()

        assertEquals(listOf("POST", "GET"), ran)
    }

    private fun redirect(request: Request, code: Int, location: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Redirect")
        .header("Location", location)
        .body("".toResponseBody(null))
        .build()

    private fun page(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("<html></html>".toResponseBody(null))
        .build()

    private companion object {
        const val SIGN_IN_URL = "https://ssoam2.ntust.edu.tw/account/login"
        const val CALLBACK_URL = "https://ssoam2.ntust.edu.tw/connect/authorize/callback"
    }
}
