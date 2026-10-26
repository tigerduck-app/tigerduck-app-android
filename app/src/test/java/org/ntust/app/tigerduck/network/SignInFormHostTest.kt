@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.network

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import java.util.concurrent.CopyOnWriteArrayList
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The sign-in form is posted only to the school's sign-in host, over HTTPS, as on the iPhone,
 * whatever the page names as the form's action. Hand-made pages throughout.
 */
class SignInFormHostTest {

    private val context = JvmContext()

    private val posts = CopyOnWriteArrayList<String>()

    private fun client(action: String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        if (request.method == "POST") posts += request.url.toString()
        respond(request, LOGIN_URL, loginPage(action))
    }.build()

    private fun respond(request: Request, url: String, html: String): Response = Response.Builder()
        .request(request.newBuilder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun latch(): PersistedStoredPasswordLatch {
        val prefs = context.getSharedPreferences("form-host-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return PersistedStoredPasswordLatch(prefs, now = { 1_800_000_000_000L }) { STUDENT to PASSWORD }
    }

    private fun ssoLogin(action: String): SsoLoginService {
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val school = client(action)
        return SsoLoginService(sessionManager, latch()) { school }
    }

    @Test
    fun `the school sign-in posts nothing to another host`() = runTest {
        val failure = runCatching {
            ssoLogin("https://elsewhere.example/account/login").signIn(SERVICE, STUDENT, PASSWORD)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `the school sign-in posts nothing over plain HTTP`() = runTest {
        val failure = runCatching {
            ssoLogin("http://ssoam2.ntust.edu.tw/account/login").ensureServiceLogin(SERVICE, STUDENT, PASSWORD)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `the school sign-in posts to its own host`() = runTest {
        runCatching { ssoLogin("/account/login").signIn(SERVICE, STUDENT, PASSWORD) }

        assertEquals(listOf("https://ssoam2.ntust.edu.tw/account/login"), posts)
    }

    @Test
    fun `the Moodle token sign-in posts nothing to another host`() = runTest(UnconfinedTestDispatcher()) {
        val tokens = object : MoodleTokenPairStore {
            override var token: String? = null
            override var privateToken: String? = null
            override val studentId: String? = STUDENT
            override val password: String? = PASSWORD
            override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?) = true
        }
        val school = client("https://elsewhere.example/account/login")
        val service = MoodleTokenService({ school }, tokens, latch(), backgroundScope) { 0L }

        val failure = runCatching { service.obtainToken(STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MalformedResponse)
        assertEquals(emptyList<String>(), posts)
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "password"
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"

        fun loginPage(action: String) = """
            <html><body>
            <form id="loginForm" action="$action" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-antiforgery" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()
    }
}
