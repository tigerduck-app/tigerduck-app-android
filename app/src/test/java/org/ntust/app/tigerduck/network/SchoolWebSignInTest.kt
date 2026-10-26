package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.connection.RealCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SsoLoginService.signInForBrowser]: a school site signed in for the TigerDuck browser with the
 * SSO session the app already holds, against hand-made SSO and site pages. OkHttp's own cookie
 * handling is played by the fake school, which reads and keeps cookies in the jar of the client
 * that made the call, as OkHttp's bridge does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class, OkHttpInternalApi::class)
class SchoolWebSignInTest {

    /** Every request the school got, as "METHOD url". */
    private val requests = CopyOnWriteArrayList<String>()

    /** Password posts to SSO, which a sign-in for the browser never makes. */
    private val passwordPosts = CopyOnWriteArrayList<String>()

    /** The app's shared jar, where its SSO session is kept. */
    private val appJar = SignInCookieJar()

    /** The jars the service made, one per run. */
    private val jars = mutableListOf<SignInCookieJar>()

    /** Where SSO's bridge form posts, for a browser that holds its session. */
    private var bridgeAction = CALLBACK

    private val school = OkHttpClient.Builder().cookieJar(appJar).addInterceptor { chain -> answer(chain) }.build()

    private fun service(): SsoLoginService {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("school-web-sign-in-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val latch = PersistedStoredPasswordLatch(prefs, now = { NOW }) { STUDENT to PASSWORD }
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        return SsoLoginService(sessionManager, latch, newCookieJar = { SignInCookieJar().also { jars += it } }) { school }
    }

    /** The app signed in to SSO earlier, for another school site: its jar holds SSO's session, and a cookie of the whole domain's. */
    private fun appHoldsSsoSession() {
        val sso = "https://$SSO_HOST/account/login".toHttpUrl()
        appJar.saveFromResponse(sso, listOfNotNull(Cookie.parse(sso, "$SSO_COOKIE=sso-made-up; Path=/; Secure; HttpOnly")))
        // Built, not parsed: a Domain attribute sends OkHttp's parser to the public suffix list, which tests lack.
        appJar.saveFromResponse(sso, listOf(Cookie.Builder().name("shared").value("made-up").domain("ntust.edu.tw").path("/").secure().build()))
    }

    @Test
    fun `with the app's SSO session it posts no password and hands back the site's cookies alone`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()

        val cookies = service().signInForBrowser(SITE_PAGE)

        assertEquals(listOf(SITE_COOKIE), cookies.map { it.name })
        assertTrue(cookies.all { it.domain == SITE_HOST })
        assertEquals("no password posted", emptyList<String>(), passwordPosts)
        assertEquals("POST $CALLBACK", requests.single { it.startsWith("POST") })
    }

    @Test
    fun `with no SSO session held, SSO's sign-in form ends it and nothing is posted`() = runTest(UnconfinedTestDispatcher()) {
        expectFailure(SsoLoginError.SessionMissing::class.java) { service().signInForBrowser(SITE_PAGE) }

        assertTrue(requests.none { it.startsWith("POST") })
    }

    @Test
    fun `each run keeps its cookies in a jar of its own, and the app's jar gets nothing of them`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        val service = service()
        service.signInForBrowser(SITE_PAGE)
        service.signInForBrowser(SITE_PAGE)

        assertEquals(2, jars.size)
        assertTrue(jars[0] !== jars[1])
        assertTrue(appJar.loadForRequest(SITE_PAGE.toHttpUrl()).none { it.name == SITE_COOKIE })
    }

    @Test
    fun `a page the site serves with no sign-in hands back the site's own cookies`() = runTest(UnconfinedTestDispatcher()) {
        val cookies = service().signInForBrowser(PUBLIC_PAGE)

        assertEquals(listOf("ASP.NET_SessionId"), cookies.map { it.name })
        assertTrue(requests.none { SSO_HOST in it })
    }

    @Test
    fun `ssoam's NetIQ page is one the student signs in to, with nothing posted`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()

        expectFailure(SsoLoginError.InteractiveLoginRequired::class.java) { service().signInForBrowser(NETIQ_SITE) }
        assertTrue(requests.none { it.startsWith("POST") })
    }

    @Test
    fun `a bridge off the school's hosts is never posted`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        bridgeAction = "https://example.com/signin-oidc"

        expectFailure(SsoLoginError.InvalidResponse::class.java) { service().signInForBrowser(SITE_PAGE) }
        assertTrue(requests.none { it.startsWith("POST") })
    }

    @Test
    fun `nothing the run started with, SSO's or the domain's, is handed back`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()

        val names = service().signInForBrowser(SITE_PAGE).map { it.name }

        assertTrue(names.none { it == SSO_COOKIE || it == "shared" })
    }

    private suspend fun expectFailure(type: Class<out Throwable>, block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${type.simpleName}")
        } catch (e: Throwable) {
            if (!type.isInstance(e)) throw e
        }
    }

    private fun callersJar(chain: okhttp3.Interceptor.Chain): CookieJar = (chain.call() as RealCall).client.cookieJar

    private fun answer(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        requests += "${request.method} $url"
        val jar = callersJar(chain)
        val holdsSso = jar.loadForRequest("https://$SSO_HOST/connect/authorize".toHttpUrl()).any { it.name == SSO_COOKIE }
        val (landed, html, sets) = when {
            request.method == "POST" && request.url.host == SSO_HOST -> {
                passwordPosts += url
                Triple(SSO_LOGIN, LOGIN_PAGE, emptyList())
            }
            request.method == "POST" && url == CALLBACK ->
                Triple(SITE_PAGE, "<html><body>Student record</body></html>", listOf("$SITE_COOKIE=site-made-up; Path=/; Secure; HttpOnly"))
            // The site sends a signed-out visitor to SSO's authorize, which skips its form for a browser holding its session.
            url == SITE_PAGE ->
                if (jar.loadForRequest(request.url).any { it.name == SITE_COOKIE }) {
                    Triple(SITE_PAGE, "<html><body>Student record</body></html>", emptyList())
                } else if (holdsSso) {
                    Triple("https://$SSO_HOST/connect/authorize/callback", bridgePage(bridgeAction), emptyList())
                } else {
                    Triple(SSO_LOGIN, LOGIN_PAGE, emptyList())
                }
            url == PUBLIC_PAGE -> Triple(PUBLIC_PAGE, "<html><body>Course search</body></html>", listOf("ASP.NET_SessionId=anon-made-up; Path=/; Secure"))
            url == NETIQ_SITE -> Triple(NETIQ_LOGIN, NETIQ_PAGE, emptyList())
            else -> error("unexpected $url")
        }
        val landedUrl = landed.toHttpUrl()
        jar.saveFromResponse(landedUrl, sets.mapNotNull { Cookie.parse(landedUrl, it) })
        return Response.Builder()
            .request(request.newBuilder().url(landedUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val STUDENT = "B00015999"
        const val PASSWORD = "made-up-password"
        const val SSO_HOST = "ssoam2.ntust.edu.tw"
        const val SSO_COOKIE = ".AspNetCore.Identity.Application"
        const val SSO_LOGIN = "https://$SSO_HOST/account/login?ReturnUrl=%2Fconnect"
        const val SITE_HOST = "stuinfosys.ntust.edu.tw"
        const val SITE_PAGE = "https://$SITE_HOST/StudentInformation"
        const val CALLBACK = "https://$SITE_HOST/signin-oidc"
        const val SITE_COOKIE = ".AspNetCore.Cookies"
        const val PUBLIC_PAGE = "https://querycourse.ntust.edu.tw/QueryCourse/"
        const val NETIQ_SITE = "https://netiq-service.ntust.edu.tw/"
        const val NETIQ_LOGIN = "https://ssoam.ntust.edu.tw/nidp/app/login"

        fun bridgePage(action: String) = """
            <html><body onload="document.forms[0].submit()">
            <form method="post" action="$action">
              <input type="hidden" name="id_token" value="made-up-token" />
              <input type="hidden" name="state" value="made-up-state" />
            </form>
            </body></html>
        """.trimIndent()

        val LOGIN_PAGE = """
            <html><body>
            <form id="loginForm" action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-antiforgery" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()

        val NETIQ_PAGE = """
            <html><body>
            <form id="IDPLogin" action="/nidp/app/login" method="post">
              <input type="text" name="Ecom_User_ID" />
              <input type="password" name="Ecom_Password" />
              <button data-bind="loginButton2">Sign in</button>
            </form>
            </body></html>
        """.trimIndent()
    }
}
