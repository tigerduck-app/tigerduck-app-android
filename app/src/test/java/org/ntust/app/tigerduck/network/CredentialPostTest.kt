package org.ntust.app.tigerduck.network

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [executeSignInFormPost] against a stand-in school that answers as an application interceptor,
 * which is ahead of OkHttp's own redirect and retry handling. Hand-made answers throughout.
 */
class CredentialPostTest {

    private val guard = AccountSessionGuard()

    private fun client(answer: (Interceptor.Chain) -> Response) = OkHttpClient.Builder()
        .addInterceptor(guard.markCall)
        .addInterceptor { chain -> answer(chain) }
        .build()

    private fun post() = Request.Builder()
        .url(SIGN_IN_URL)
        .post(FormBody.Builder().add("Username", "B00000000").add("Password", "password").build())
        .build()

    @Test
    fun `the GET after the form keeps the account the POST started under`() {
        var getStartedUnderDepartedAccount: Boolean? = null
        val school = client { chain ->
            val request = chain.request()
            if (request.method == "POST") {
                // The account signs out while the school answers the form.
                guard.nextAccount()
                redirect(request, 302, CALLBACK_URL)
            } else {
                getStartedUnderDepartedAccount = guard.isStale(request)
                page(request)
            }
        }

        school.executeSignInFormPost(post())!!.close()

        // So the session's guard refuses it, and the departed account's cookies stay out.
        assertEquals(true, getStartedUnderDepartedAccount)
    }

    @Test
    fun `a connection that drops after the form went out does not send it again`() {
        var posts = 0
        val school = client { chain ->
            posts++
            if (posts == 1) {
                // The connection drops once the form is out. OkHttp sends it again when the client
                // retries on connection failure (RetryAndFollowUpInterceptor.recover); this
                // stand-in, answering ahead of it, does the same.
                if (!chain.retryOnConnectionFailure) throw IOException("connection reset")
                posts++
            }
            page(chain.request())
        }

        val failure = runCatching { school.executeSignInFormPost(post())?.close() }.exceptionOrNull()

        assertTrue("got $failure", failure is IOException)
        assertEquals("each post counts toward the account's lockout", 1, posts)
    }

    @Test
    fun `a sign-in host that keeps sending the form back to itself ends the sign-in`() {
        var posts = 0
        val school = client { chain ->
            posts++
            redirect(chain.request(), 307, SIGN_IN_URL)
        }

        assertNull(school.executeSignInFormPost(post()))
        assertEquals(4, posts)
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
