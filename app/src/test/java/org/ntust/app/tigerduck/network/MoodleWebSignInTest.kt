package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MoodleTokenService]'s sign-in to Moodle's website with the SSO session the app already holds,
 * for the TigerDuck browser when no autologin key can be had, against hand-made SSO and Moodle
 * pages. OkHttp's own cookie handling is played here by the fake school, which reads and keeps
 * cookies in the jar of the client that made the call, as OkHttp's bridge does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleWebSignInTest {

    private lateinit var latchPrefs: SharedPreferences
    private val tokens = Tokens()

    /** Password posts to SSO, which a sign-in to the website never makes. */
    private val posts = CopyOnWriteArrayList<String>()

    /** The OIDC bridge's posts to Moodle, which carry SSO's code and no password. */
    private val bridgePosts = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()

    /** The jars the service made, one per run. */
    private val jars = mutableListOf<SignInCookieJar>()

    /** What SSO's authorize answers a browser holding its session with. */
    private var ssoAuthorize: () -> Pair<String, String> = { BRIDGE_URL to BRIDGE_PAGE }

    /** Whether Moodle keeps the session it set. */
    private var moodleHoldsSession = true

    /** Runs as Moodle's OIDC entry is asked for, before its answer. */
    private var onEntry: () -> Unit = {}

    /** How long Moodle's OIDC entry blocks, cancelled or not, as a stuck lookup of its address does. */
    private var entryBlocksMs = 0L

    /** The app's shared jar, where its SSO session is kept. */
    private val appJar = SignInCookieJar()

    private val school = OkHttpClient.Builder().cookieJar(appJar).addInterceptor { chain -> answer(chain) }.build()

    /** What Moodle's profile editor answers a signed-in request with. */
    private var editorPage = EDITOR_PAGE

    private class Tokens : MoodleTokenPairStore {
        override var token: String? = "made-up-wstoken"
        override var privateToken: String? = null
        override var studentId: String? = STUDENT
        override var password: String? = PASSWORD

        override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?): Boolean {
            if (this.studentId != studentId) return false
            this.token = token
            this.privateToken = privateToken
            return true
        }
    }

    @Before
    fun setUp() {
        latchPrefs = RuntimeEnvironment.getApplication().getSharedPreferences("moodle-web-latch-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
    }

    /** The app signed in to SSO earlier, for another school site: its jar holds SSO's session. */
    private fun appHoldsSsoSession() {
        val sso = "https://$SSO_HOST/account/login".toHttpUrl()
        appJar.saveFromResponse(sso, listOfNotNull(Cookie.parse(sso, "$SSO_COOKIE=sso-made-up; Path=/; Secure; HttpOnly")))
    }

    private fun TestScope.service(): MoodleTokenService {
        val latch = PersistedStoredPasswordLatch(latchPrefs, now = { NOW }) {
            val id = tokens.studentId
            val password = tokens.password
            if (id == null || password == null) null else id to password
        }
        return MoodleTokenService(
            clientFor = { school },
            tokenStore = tokens,
            passwordLatch = latch,
            scope = backgroundScope,
            newCookieJar = { SignInCookieJar().also { jars += it } },
            now = { NOW },
        )
    }

    @Test
    fun `with the app's SSO session it posts no password and hands back Moodle's session, nothing of SSO's`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()

        val cookies = service().signInToWebsite()

        assertEquals(listOf("MoodleSession", "MOODLEID1_"), cookies.map { it.name })
        assertTrue(cookies.all { it.domain == MOODLE_HOST })
        assertEquals("no password posted", emptyList<String>(), posts)
        assertEquals(listOf("$MOODLE/auth/oidc/"), bridgePosts)
        // Signed in, checked on a page only a signed-in student gets.
        assertEquals("$MOODLE/user/edit.php", requests.last())
    }

    @Test
    fun `with no SSO session held, SSO's sign-in form ends it and nothing is posted`() = runTest(UnconfinedTestDispatcher()) {
        expectFailure(SsoSessionMissing::class.java) { service().signInToWebsite() }

        assertEquals(emptyList<String>(), posts)
        assertEquals(emptyList<String>(), bridgePosts)
    }

    @Test
    fun `each run keeps its cookies in a jar of its own, and the app's jar gets nothing of them`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        val service = service()
        service.signInToWebsite()
        service.signInToWebsite()

        assertEquals(2, jars.size)
        assertTrue(jars[0] !== jars[1])
        assertEquals(emptyList<Cookie>(), appJar.loadForRequest("$MOODLE/".toHttpUrl()))
    }

    @Test
    fun `a page of SSO's own in place of the bridge, a second factor say, is a failure with nothing posted`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        ssoAuthorize = { "https://$SSO_HOST/account/mfa" to MFA_PAGE }

        expectFailure(SsoSessionMissing::class.java) { service().signInToWebsite() }
        assertEquals(emptyList<String>(), posts)
        assertEquals(emptyList<String>(), bridgePosts)
    }

    @Test
    fun `a bridge off the school's hosts is never posted`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        ssoAuthorize = { BRIDGE_URL to BRIDGE_PAGE.replace("$MOODLE/auth/oidc/", "https://example.com/auth/oidc/") }

        expectFailure(MoodleWebserviceError.MalformedResponse::class.java) { service().signInToWebsite() }
        assertEquals(emptyList<String>(), bridgePosts)
    }

    @Test
    fun `a session Moodle does not hold is a failure`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        moodleHoldsSession = false

        expectFailure(MoodleWebserviceError.MalformedResponse::class.java) { service().signInToWebsite() }
    }

    @Test
    fun `a page at the editor's address that is not the editor is a failure, session cookie or not`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        // A challenge the school's gateway serves in place of the page, say.
        editorPage = "<html><body><h1>Checking your browser</h1></body></html>"

        expectFailure(MoodleWebserviceError.MalformedResponse::class.java) { service().signInToWebsite() }
    }

    @Test
    fun `a sign-out during the run keeps nothing of it`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        onEntry = { tokens.studentId = null }

        expectFailure(MoodleWebserviceError.MissingStoredCredentials::class.java) { service().signInToWebsite() }
    }

    @Test
    fun `with no account stored nothing is asked for`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        tokens.studentId = null

        expectFailure(MoodleWebserviceError.MissingStoredCredentials::class.java) { service().signInToWebsite() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a run stuck where a cancel cannot reach lets go as soon as it is cancelled`() = runTest(UnconfinedTestDispatcher()) {
        appHoldsSsoSession()
        val service = service()
        entryBlocksMs = 4_000

        val started = System.nanoTime()
        val failed = onTheClock { runCatching { withTimeout(300) { service.signInToWebsite() } } }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(failed.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue("let go at the deadline, not after the stuck call ($tookMs ms)", tookMs < 2_000)
        assertEquals(emptyList<String>(), bridgePosts)
    }

    /** [block] timed on the clock rather than the test's virtual time, as the run is. */
    private suspend fun <T> onTheClock(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1), block)

    private suspend fun expectFailure(type: Class<out Throwable>, block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${type.simpleName}")
        } catch (e: Throwable) {
            if (!type.isInstance(e)) throw e
        }
    }

    // --- the fake school: SSO and Moodle, as OkHttp would see them after following redirects ---

    /**
     * The jar of the client that made the call, where OkHttp's bridge would read and keep its
     * cookies: so a run that used the app's jar in place of its own would show in [appJar].
     * OkHttp names no public way to it.
     */
    @OptIn(OkHttpInternalApi::class)
    private fun callersJar(chain: okhttp3.Interceptor.Chain): CookieJar = (chain.call() as RealCall).client.cookieJar

    private fun answer(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        requests += url
        val jar = callersJar(chain)
        val (landed, html, sets) = when {
            request.method == "POST" && request.url.host == SSO_HOST -> {
                posts += url
                Triple(BRIDGE_URL, BRIDGE_PAGE, listOf("$SSO_COOKIE=sso-made-up; Path=/; Secure; HttpOnly"))
            }
            request.method == "POST" && url.startsWith("$MOODLE/auth/oidc/") -> {
                bridgePosts += url
                Triple(
                    "$MOODLE/my/",
                    "<html><body>Dashboard</body></html>",
                    listOf("MoodleSession=fake-session-000; Path=/; Secure; HttpOnly", "MOODLEID1_=made-up; Path=/; Secure"),
                )
            }
            url.startsWith("$MOODLE/auth/oidc/") -> {
                if (entryBlocksMs > 0) {
                    Thread.sleep(entryBlocksMs)
                    entryBlocksMs = 0
                    if (chain.call().isCanceled()) throw java.io.IOException("Canceled")
                }
                onEntry()
                // SSO's authorize, which skips its sign-in form for a browser that holds its session.
                val sso = "https://$SSO_HOST/connect/authorize".toHttpUrl()
                if (jar.loadForRequest(sso).any { it.name == SSO_COOKIE }) {
                    val (at, page) = ssoAuthorize()
                    Triple(at, page, emptyList())
                } else {
                    Triple(SSO_LOGIN_URL, LOGIN_PAGE, emptyList())
                }
            }
            url == "$MOODLE/user/edit.php" -> {
                val sent = jar.loadForRequest(request.url).any { it.name == "MoodleSession" }
                if (sent && moodleHoldsSession) Triple(url, editorPage, emptyList())
                else Triple(SSO_LOGIN_URL, LOGIN_PAGE, emptyList())
            }
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
        const val MOODLE_HOST = "moodle2.ntust.edu.tw"
        const val MOODLE = "https://$MOODLE_HOST"
        const val SSO_HOST = "ssoam2.ntust.edu.tw"
        const val SSO_COOKIE = ".AspNetCore.Identity.Application"
        const val SSO_LOGIN_URL = "https://$SSO_HOST/account/login?ReturnUrl=%2Fconnect"
        const val BRIDGE_URL = "https://$SSO_HOST/connect/authorize/callback"

        val LOGIN_PAGE = """
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

        val BRIDGE_PAGE = """
            <html><body onload="document.forms[0].submit()">
            <form method="post" action="$MOODLE/auth/oidc/">
              <input type="hidden" name="code" value="made-up-code" />
              <input type="hidden" name="state" value="made-up-state" />
              <input type="hidden" name="iss" value="https://$SSO_HOST" />
            </form>
            </body></html>
        """.trimIndent()

        /** Moodle's profile editor as it opens signed in: its body, and its form with the session key. */
        val EDITOR_PAGE = """
            <html><body id="page-user-edit" class="format-site path-user loggedin">
            <form autocomplete="off" action="$MOODLE/user/edit.php" method="post" accept-charset="utf-8">
              <input name="sesskey" type="hidden" value="made-up-sesskey" />
            </form>
            </body></html>
        """.trimIndent()

        val MFA_PAGE = """
            <html><body>
            <form action="/account/mfa" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-antiforgery" />
              <input type="text" name="Code" />
            </form>
            </body></html>
        """.trimIndent()
    }
}
