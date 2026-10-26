package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * The bridge forms that pass a sign-in on to a service, carrying what the school hands it (an ID
 * token, an OIDC code): posted only to the school's hosts over HTTPS, and no 307 or 308 sends one
 * on, for the school sign-in and the Moodle token's. As on the iPhone. Hand-made pages throughout.
 *
 * The school stands in as an application interceptor, which answers ahead of OkHttp's own
 * redirect handling, so it follows a redirect itself the way OkHttp does whenever the client it
 * answers for follows redirects: a 307 or a 308 with the same method and form, any other as a GET.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SignInBridgeFormTest {

    /** Every request the school got, as "METHOD url". */
    private val requests = CopyOnWriteArrayList<String>()

    /** Where the bridge form on the page posts. */
    private var bridgeAction = SERVICE_SIGN_IN

    /** What the school answers the bridge form's POST with. */
    private var afterBridge: (Request) -> Response = { redirect(it, 302, if (moodle) MOODLE_CONFIRMED else SERVICE_SIGNED_IN) }

    /** Whether the Moodle token's sign-in is the one under test. */
    private var moodle = false

    private val school: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> followingRedirects(chain) }.build()

    private val latch: PersistedStoredPasswordLatch by lazy {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("bridge-form-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        PersistedStoredPasswordLatch(prefs, now = { 1_800_000_000_000L }) { STUDENT to PASSWORD }
    }

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
        requests += "${request.method} ${request.url}"
        val url = request.url.toString()
        return when {
            request.method == "POST" && url == bridgeAction -> afterBridge(request)
            // The school sign-in's service, for an account SSO already holds a session for.
            url == SERVICE -> page(request, ssoBridgePage(bridgeAction))
            url == SERVICE_SIGNED_IN -> page(request, "<html><body>Course selection</body></html>")
            // The Moodle token's: the launch, SSO's form, and the bridge back to Moodle.
            url.startsWith(MOODLE_LAUNCH) && "confirmed=1" in url -> page(request, tokenPage())
            url.startsWith(MOODLE_LAUNCH) -> redirect(request, 302, LOGIN_URL)
            url.startsWith(LOGIN_URL) -> page(request, MOODLE_LOGIN_PAGE)
            request.method == "POST" && url == POST_URL -> redirect(request, 302, CALLBACK_URL)
            url == CALLBACK_URL -> page(request, moodleBridgePage(bridgeAction))
            else -> page(request, "<html><body>Somewhere else</body></html>")
        }
    }

    private fun ssoLogin(): SsoLoginService {
        val context = RuntimeEnvironment.getApplication()
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        return SsoLoginService(sessionManager, latch) { school }
    }

    private fun TestScope.moodleTokens(action: String): MoodleTokenService {
        moodle = true
        bridgeAction = action
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
        return MoodleTokenService({ school }, tokens, latch, backgroundScope) { 1_800_000_000_000L }
    }

    private val posts get() = requests.filter { it.startsWith("POST") }

    @Test
    fun `a bridge form posts to the school's service and signs in`() = runTest {
        assertTrue(ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(listOf("POST $SERVICE_SIGN_IN"), posts)
        assertTrue(requests.contains("GET $SERVICE_SIGNED_IN"))
    }

    @Test
    fun `a bridge form posts nothing off the school's hosts`() = runTest {
        bridgeAction = "https://elsewhere.example/signin-oidc"

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a bridge form posts nothing to a host named like the school's`() = runTest {
        bridgeAction = "https://courseselection.ntust.edu.tw.elsewhere.example/signin-oidc"

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a bridge form posts nothing over plain HTTP`() = runTest {
        bridgeAction = "http://courseselection.ntust.edu.tw/signin-oidc"

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a 307 after a bridge form sends its token nowhere else`() = runTest {
        afterBridge = { redirect(it, 307, ELSEWHERE) }

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(listOf("POST $SERVICE_SIGN_IN"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
    }

    @Test
    fun `a 308 after a bridge form is not followed to the school's own host either`() = runTest {
        afterBridge = { redirect(it, 308, "/signin-oidc?node=2") }

        val failure = runCatching { ssoLogin().ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.InvalidResponse)
        assertEquals(listOf("POST $SERVICE_SIGN_IN"), posts)
    }

    @Test
    fun `the bridge back to Moodle posts its code to Moodle`() = runTest(UnconfinedTestDispatcher()) {
        val service = moodleTokens(MOODLE_BRIDGE)

        assertEquals(WSTOKEN, service.obtainToken(STUDENT, PASSWORD))

        assertEquals(listOf("POST $POST_URL", "POST $MOODLE_BRIDGE"), posts)
    }

    @Test
    fun `the bridge back to Moodle posts nothing over plain HTTP, and is no refused password`() = runTest(UnconfinedTestDispatcher()) {
        val service = moodleTokens("http://moodle2.ntust.edu.tw/auth/oidc/")

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MalformedResponse)
        assertEquals(listOf("POST $POST_URL"), posts)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `the bridge back to Moodle posts nothing off the school's hosts`() = runTest(UnconfinedTestDispatcher()) {
        val service = moodleTokens("https://elsewhere.example/auth/oidc/")

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MalformedResponse)
        assertEquals(listOf("POST $POST_URL"), posts)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a 307 after the bridge back to Moodle sends its code nowhere else`() = runTest(UnconfinedTestDispatcher()) {
        val service = moodleTokens(MOODLE_BRIDGE)
        afterBridge = { redirect(it, 307, ELSEWHERE) }

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MalformedResponse)
        assertEquals(listOf("POST $POST_URL", "POST $MOODLE_BRIDGE"), posts)
        assertTrue(requests.none { it.contains("elsewhere.example") })
        assertNull(latch.rejection.value)
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
        const val SERVICE_SIGN_IN = "https://courseselection.ntust.edu.tw/signin-oidc"
        const val SERVICE_SIGNED_IN = "https://courseselection.ntust.edu.tw/home"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val POST_URL = "https://ssoam2.ntust.edu.tw/account/login"
        const val CALLBACK_URL = "https://ssoam2.ntust.edu.tw/connect/authorize/callback"
        const val ELSEWHERE = "https://elsewhere.example/collect"
        const val MOODLE_LAUNCH = "https://moodle2.ntust.edu.tw/admin/tool/mobile/launch.php"
        const val MOODLE_CONFIRMED = "$MOODLE_LAUNCH?confirmed=1"
        const val MOODLE_BRIDGE = "https://moodle2.ntust.edu.tw/auth/oidc/"

        fun ssoBridgePage(action: String) = """
            <html><body onload="document.forms[0].submit()">
            <form method="post" action="$action">
              <input type="hidden" name="id_token" value="made-up-id-token" />
              <input type="hidden" name="state" value="made-up-state" />
            </form>
            </body></html>
        """.trimIndent()

        fun moodleBridgePage(action: String) = """
            <html><body onload="document.forms[0].submit()">
            <form method="post" action="$action">
              <input type="hidden" name="code" value="made-up-code" />
              <input type="hidden" name="state" value="made-up-state" />
              <input type="hidden" name="iss" value="https://ssoam2.ntust.edu.tw" />
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
