package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What answers the sign-in form's POST, for the school sign-in and the Moodle token's: a 307 or a
 * 308 has the form sent again only to the school's sign-in host itself, a 301, 302 or 303 is
 * followed as a GET that carries no form, and only over HTTPS. Hand-made pages throughout.
 *
 * The school stands in as an application interceptor, which answers ahead of OkHttp's own
 * redirect handling, so it follows a redirect itself the way OkHttp does whenever the client it
 * answers for follows redirects: a 307 or a 308 with the same method and form, any other as a GET.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SignInFormRedirectTest {

    /** Every request the school got, as "METHOD url", with " +password" when it carried it. */
    private val requests = CopyOnWriteArrayList<String>()

    /** What the school answers the sign-in form's POST with. */
    private var afterPost: (Request) -> Response = { redirect(it, 302, CALLBACK_URL) }

    private val school: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> followingRedirects(chain) }.build()

    private fun followingRedirects(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = answer(request)
        while (chain.followRedirects && response.isRedirect) {
            val next = request.url.resolve(response.header("Location")!!)!!
            if (next.scheme != request.url.scheme && !chain.followSslRedirects) break
            response.close()
            request = if (response.code == 307 || response.code == 308) {
                request.newBuilder().url(next).build()
            } else {
                request.newBuilder().url(next).get().build()
            }
            response = answer(request)
        }
        return response
    }

    private fun answer(request: Request): Response {
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        requests += "${request.method} ${request.url}" + if ("Password=" in body) " +password" else ""
        val url = request.url.toString()
        return when {
            request.method == "POST" && url == POST_URL -> afterPost(request)
            request.method == "POST" && url == SECOND_POST_URL -> redirect(request, 302, CALLBACK_URL)
            url == SERVICE -> redirect(request, 302, LOGIN_URL)
            url.startsWith(LOGIN_URL) -> page(request, if (moodle) MOODLE_LOGIN_PAGE else SSO_LOGIN_PAGE)
            url == CALLBACK_URL -> redirect(request, 302, if (moodle) MOODLE_CONFIRMED else SERVICE_SIGNED_IN)
            url == SERVICE_SIGNED_IN -> page(request, "<html><body>Course selection</body></html>")
            url.startsWith(MOODLE_LAUNCH) && "confirmed=1" in url -> page(request, tokenPage())
            url.startsWith(MOODLE_LAUNCH) -> redirect(request, 302, LOGIN_URL)
            else -> page(request, "<html><body>Somewhere else</body></html>")
        }
    }

    /** Whether the Moodle token's sign-in is the one under test. */
    private var moodle = false

    private fun latch(): PersistedStoredPasswordLatch {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("form-redirect-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return PersistedStoredPasswordLatch(prefs, now = { 1_800_000_000_000L }) { STUDENT to PASSWORD }
    }

    private fun ssoLogin(): SsoLoginService {
        val context = RuntimeEnvironment.getApplication()
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        return SsoLoginService(sessionManager, latch()) { school }
    }

    private fun kotlinx.coroutines.test.TestScope.moodleTokens(): MoodleTokenService {
        moodle = true
        val tokens = object : MoodleTokenPairStore {
            override var token: String? = null
            override var privateToken: String? = null
            override val studentId: String = STUDENT
            override val password: String = PASSWORD
            override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?) = true.also {
                this.token = token
                this.privateToken = privateToken
            }
        }
        return MoodleTokenService({ school }, tokens, latch(), backgroundScope) { 1_800_000_000_000L }
    }

    private val posts get() = requests.filter { it.startsWith("POST") }

    @Test
    fun `a 307 after the school sign-in form sends the form nowhere else`() = runTest {
        afterPost = { redirect(it, 307, ELSEWHERE) }

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    @Test
    fun `a 308 after the school sign-in form sends the form nowhere else`() = runTest {
        afterPost = { redirect(it, 308, ELSEWHERE) }

        val failure = runCatching { ssoLogin().signIn(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    @Test
    fun `a 307 after the school sign-in form to the sign-in host itself sends it there again`() = runTest {
        // Relative, as a load balancer in front of the sign-in host may send it.
        afterPost = { redirect(it, 307, "/account/login?node=2") }

        assertTrue(ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(listOf("POST $POST_URL +password", "POST $SECOND_POST_URL +password"), posts)
        assertTrue(requests.contains("GET $SERVICE_SIGNED_IN"))
    }

    @Test
    fun `a 308 after the school sign-in form to the sign-in host over plain HTTP sends it nowhere`() = runTest {
        afterPost = { redirect(it, 308, "http://ssoam2.ntust.edu.tw/account/login?node=2") }

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.none { it.contains("http://") })
    }

    @Test
    fun `a 301 after the school sign-in form is followed as a GET`() = runTest {
        afterPost = { redirect(it, 301, CALLBACK_URL) }

        assertTrue(ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.contains("GET $CALLBACK_URL"))
    }

    @Test
    fun `a redirect after the school sign-in form to a relative address is followed on the sign-in host`() = runTest {
        afterPost = { redirect(it, 302, "/connect/authorize/callback") }

        assertTrue(ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.contains("GET $CALLBACK_URL"))
    }

    @Test
    fun `a 302 after the school sign-in form is followed as a GET`() = runTest {
        assertTrue(ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.contains("GET $CALLBACK_URL"))
        assertTrue(requests.contains("GET $SERVICE_SIGNED_IN"))
    }

    @Test
    fun `a redirect after the school sign-in form to plain HTTP is not followed`() = runTest {
        afterPost = { redirect(it, 303, "http://ssoam2.ntust.edu.tw/connect/authorize/callback") }

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertTrue(requests.none { it.contains("http://") })
    }

    @Test
    fun `a 307 after the Moodle token sign-in form sends the form nowhere else`() = runTest(UnconfinedTestDispatcher()) {
        val service = moodleTokens()
        afterPost = { redirect(it, 307, ELSEWHERE) }

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MalformedResponse)
        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    @Test
    fun `a 302 after the Moodle token sign-in form is followed as a GET`() = runTest(UnconfinedTestDispatcher()) {
        val service = moodleTokens()

        assertEquals(WSTOKEN, service.obtainToken(STUDENT, PASSWORD))

        assertEquals(listOf("POST $POST_URL +password"), posts)
        assertTrue(requests.contains("GET $CALLBACK_URL"))
    }

    private fun redirect(request: Request, code: Int, location: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Redirect")
        .header("Location", location)
        .body("".toResponseBody(null))
        .build()

    private fun page(request: Request, html: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun tokenPage(): String {
        val payload = Base64.encodeToString("signature:::$WSTOKEN:::private".toByteArray(), Base64.NO_WRAP)
        return """<html><body><a href="moodlemobile://token=$payload">Open</a></body></html>"""
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "password"
        const val WSTOKEN = "made-up-wstoken"
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val SERVICE_SIGNED_IN = "https://courseselection.ntust.edu.tw/signin-oidc"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val POST_URL = "https://ssoam2.ntust.edu.tw/account/login"
        const val SECOND_POST_URL = "https://ssoam2.ntust.edu.tw/account/login?node=2"
        const val CALLBACK_URL = "https://ssoam2.ntust.edu.tw/connect/authorize/callback"
        const val ELSEWHERE = "https://elsewhere.example/collect"
        const val MOODLE_LAUNCH = "https://moodle2.ntust.edu.tw/admin/tool/mobile/launch.php"
        const val MOODLE_CONFIRMED = "$MOODLE_LAUNCH?confirmed=1"

        val SSO_LOGIN_PAGE = """
            <html><body>
            <form id="loginForm" action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-token" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()

        val MOODLE_LOGIN_PAGE = """
            <html><body>
            <form action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-antiforgery" />
              <input type="hidden" name="ClientId" value="moodle" />
              <input type="hidden" name="ReturnUrl" value="/connect" />
              <input type="hidden" name="Uri" value="" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()
    }
}
