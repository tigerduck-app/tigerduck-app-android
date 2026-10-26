package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.auth.SIGN_IN_CALL_TIMEOUT_SECONDS
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * [MoodleTokenService]'s Moodle token harvest against hand-made Moodle and SSO pages, with the
 * app-wide stored-password latch: what its renewals post, and what a sign-in by hand stores
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/163, #212 and #300), and how
 * long each of its calls may take (#366).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleTokenServiceLatchTest {

    private lateinit var latchPrefs: SharedPreferences
    private val tokens = Tokens()
    private val posts = CopyOnWriteArrayList<String>()

    /** Whether SSO takes the password posted to it. */
    private var accepts = true

    /** Runs as each SSO sign-in arrives, before its answer. */
    private var onPost: () -> Unit = {}

    /** What the launch lands on: the sign-in form, unless a test swaps it. */
    private var landing = LOGIN_PAGE

    /** Runs as the launch arrives, before its answer. */
    private var onLaunch: () -> Unit = {}

    /** The private token Moodle hands over with the wstoken, or null for a pair without one. */
    private var handedPrivateToken: String? = "private"

    /** Each call's deadline for the whole call, in nanoseconds; 0 for none. */
    private val deadlines = CopyOnWriteArrayList<Long>()

    private val school = OkHttpClient.Builder().addInterceptor { chain ->
        deadlines += chain.call().timeout().timeoutNanos()
        answer(chain.request())
    }.build()

    private class Tokens : MoodleTokenPairStore {
        override var token: String? = null
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
        latchPrefs = RuntimeEnvironment.getApplication().getSharedPreferences("moodle-latch-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
    }

    /** A service and latch as a fresh process has them, over the same persisted latch. */
    private fun TestScope.service(): Pair<MoodleTokenService, PersistedStoredPasswordLatch> {
        val latch = PersistedStoredPasswordLatch(latchPrefs, now = { 1_800_000_000_000L }) {
            val id = tokens.studentId
            val password = tokens.password
            if (id == null || password == null) null else id to password
        }
        val service = MoodleTokenService(
            clientFor = { school },
            tokenStore = tokens,
            passwordLatch = latch,
            scope = backgroundScope,
            now = { 1_800_000_000_000L },
        )
        return service to latch
    }

    @Test
    fun `a renewal signs in with the stored account and stores the token`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()

        assertEquals(WSTOKEN, service.renewToken(null))

        assertEquals(WSTOKEN, tokens.token)
        assertEquals(1, posts.size)
    }

    @Test
    fun `the private token Moodle hands over with the token is stored beside it`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()

        service.renewToken(null)
        assertEquals("private", tokens.privateToken)
        assertEquals("private", service.currentPrivateToken())

        tokens.privateToken = null
        service.obtainToken(STUDENT, PASSWORD)
        assertEquals("a sign-in by hand stores it too", "private", tokens.privateToken)
    }

    @Test
    fun `each call of a renewal has a deadline for the whole call`() = runTest(UnconfinedTestDispatcher()) {
        // The automatic sign-in waits on the renewal under NonCancellable, and a school that
        // trickled its answer, each byte inside the read timeout, held it without end.
        val (service, _) = service()

        assertEquals(WSTOKEN, service.renewToken(null))

        // The launch, and the SSO sign-in's post.
        assertEquals(List(2) { TimeUnit.SECONDS.toNanos(SIGN_IN_CALL_TIMEOUT_SECONDS) }, deadlines)
    }

    @Test
    fun `a latched renewal posts no password`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a refusal stops renewals in the next process too`() = runTest(UnconfinedTestDispatcher()) {
        accepts = false
        val (first, firstLatch) = service()
        val failure = runCatching { first.renewToken(null) }.exceptionOrNull()
        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(REASON, firstLatch.rejection.value?.reason)
        assertEquals(1, posts.size)

        // The app restarts: new instances, over the latch the last process left.
        accepts = true
        val (second, _) = service()
        val again = runCatching { second.renewToken(null) }.exceptionOrNull()

        assertTrue("got $again", again is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("the refused password is not posted again", 1, posts.size)
    }

    @Test
    fun `a Moodle call whose token is dead renews it through the latch`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        tokens.token = "dead"
        val context = RuntimeEnvironment.getApplication()
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val webservice = MoodleWebservice(sessionManager, service)

        val failure = runCatching {
            webservice.withToken<Unit> { throw MoodleWebserviceError.InvalidToken() }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a refusal latched while a renewal is on its way to the form stops it before the post`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        // Another sign-in's refusal lands after the renewal's first look at the latch.
        onLaunch = { latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON) }

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(REASON, (failure as MoodleWebserviceError.SsoLoginRejected).reason)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a renewal whose password a sign-in by hand replaced on the way posts nothing`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        // A sign-in by hand stores a new password, and lifts the latch, after the renewal began.
        onLaunch = {
            tokens.password = "new-password"
            latch.clear()
        }

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MissingStoredCredentials)
        assertEquals(emptyList<String>(), posts)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a sign-in page with no form to post to latches nothing, as no password went out`() = runTest(UnconfinedTestDispatcher()) {
        landing = """<html><body><div class="alert-danger">Too many attempts</div></body></html>"""
        val (service, latch) = service()

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginFormUnavailable)
        assertEquals("Too many attempts", (failure as MoodleWebserviceError.SsoLoginFormUnavailable).reason)
        assertEquals(emptyList<String>(), posts)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a sign-in by hand posts what was typed even while latched, and lifts the latch`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        assertEquals(WSTOKEN, service.obtainToken(STUDENT, "new-password"))

        assertEquals(1, posts.size)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a sign-out during the sign-in by hand stores nothing of the departing account`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()
        onPost = {
            tokens.studentId = null
            tokens.password = null
        }

        val failure = runCatching { service.obtainToken(STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MissingStoredCredentials)
        assertNull(tokens.token)
    }

    // --- a wstoken stored without its private token, as builds before 2.4.0 left it ---

    @Test
    fun `a wstoken stored without its private token gets one from a sign-in`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()
        tokens.token = WSTOKEN

        assertTrue(service.fetchPrivateToken())

        assertEquals("private", tokens.privateToken)
        assertEquals(WSTOKEN, tokens.token)
        assertEquals(1, posts.size)
    }

    @Test
    fun `a private token already stored is not fetched again`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()
        tokens.token = WSTOKEN
        tokens.privateToken = "kept"

        assertTrue(service.fetchPrivateToken())

        assertEquals("kept", tokens.privateToken)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `with no wstoken stored nothing is fetched`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()

        assertFalse(service.fetchPrivateToken())

        assertEquals(emptyList<String>(), posts)
        assertNull(tokens.token)
    }

    @Test
    fun `a pair Moodle hands over without a private token says so`() = runTest(UnconfinedTestDispatcher()) {
        handedPrivateToken = null
        val (service, _) = service()
        tokens.token = WSTOKEN

        assertFalse(service.fetchPrivateToken())

        assertEquals(WSTOKEN, tokens.token)
        assertNull(tokens.privateToken)
    }

    @Test
    fun `a refused stored password is not posted for a private token`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        tokens.token = WSTOKEN
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        val failure = runCatching { service.fetchPrivateToken() }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(emptyList<String>(), posts)
        assertEquals("the stored wstoken stays", WSTOKEN, tokens.token)
    }

    @Test
    fun `a refusal while fetching a private token keeps the wstoken and signs nobody out`() = runTest(UnconfinedTestDispatcher()) {
        accepts = false
        val (service, latch) = service()
        tokens.token = WSTOKEN

        val failure = runCatching { service.fetchPrivateToken() }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(REASON, latch.rejection.value?.reason)
        assertEquals(WSTOKEN, tokens.token)
        assertEquals(STUDENT, tokens.studentId)
        assertEquals(PASSWORD, tokens.password)

        // The next open's fetch posts nothing until the password changes.
        runCatching { service.fetchPrivateToken() }
        assertEquals(1, posts.size)
    }

    private fun answer(request: Request): Response {
        val (url, html) = when {
            request.method == "POST" -> {
                posts += request.url.toString()
                onPost()
                if (accepts) TOKEN_URL to tokenPage() else LOGIN_URL to LOGIN_PAGE_REFUSED
            }
            // The launch lands on the school's sign-in form, as for a device with no SSO session.
            else -> {
                onLaunch()
                LOGIN_URL to landing
            }
        }
        return Response.Builder()
            // As if OkHttp had followed the redirects to it.
            .request(request.newBuilder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private fun tokenPage(): String {
        val pair = listOfNotNull("signature", WSTOKEN, handedPrivateToken).joinToString(":::")
        val payload = Base64.encodeToString(pair.toByteArray(), Base64.NO_WRAP)
        return """<html><body><a href="moodlemobile://token=$payload">Open</a></body></html>"""
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "old-password"
        const val WSTOKEN = "made-up-wstoken"
        const val REASON = "Wrong account or password"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val TOKEN_URL = "https://moodle2.ntust.edu.tw/admin/tool/mobile/launch.php?confirmed=1"

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

        val LOGIN_PAGE_REFUSED = LOGIN_PAGE.replace(
            "<form",
            """<div class="validation-summary-errors"><ul><li>$REASON</li></ul></div><form""",
        )
    }
}
