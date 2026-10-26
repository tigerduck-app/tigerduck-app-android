package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The SSO sign-in the pages make and the Moodle token renewal, both automatic, meeting the same
 * refused password at once: between them the school gets it once, as each refusal counts toward
 * the account's lockout. Hand-made pages throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AutomaticPasswordPostsTest {

    private lateinit var latch: PersistedStoredPasswordLatch
    private val renewals = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val posts = CopyOnWriteArrayList<String>()

    private val ssoPosting = CountDownLatch(1)
    private val moodleAtForm = CountDownLatch(1)
    private val moodlePosted = CountDownLatch(1)

    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("automatic-posts-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        latch = PersistedStoredPasswordLatch(prefs, now = { 1_800_000_000_000L }) { STUDENT to PASSWORD }
    }

    @After
    fun tearDown() = renewals.cancel()

    /** The school as the SSO sign-in meets it: it refuses the password, slowly. */
    private val ssoSchool = client { request ->
        if (request.method == "POST") {
            posts += "sso"
            ssoPosting.countDown()
            // The renewal reaches the form while the school is still answering this post, and
            // posts too unless something holds it back.
            moodleAtForm.await(5, TimeUnit.SECONDS)
            moodlePosted.await(RACE_WINDOW_MS, TimeUnit.MILLISECONDS)
            LOGIN_URL to SSO_PAGE_REFUSED
        } else {
            LOGIN_URL to SSO_PAGE
        }
    }

    /** The school as the Moodle token renewal meets it: it refuses the password at once. */
    private val moodleSchool = client { request ->
        if (request.method == "POST") {
            posts += "moodle"
            moodlePosted.countDown()
            LOGIN_URL to MOODLE_PAGE_REFUSED
        } else {
            moodleAtForm.countDown()
            LOGIN_URL to MOODLE_PAGE
        }
    }

    @Test
    fun `an SSO sign-in and a Moodle renewal post the refused password once between them`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val sso = SsoLoginService(sessionManager, latch) { ssoSchool }
        val moodle = MoodleTokenService({ moodleSchool }, Tokens(), latch, renewals) { 1_800_000_000_000L }

        val signIn = async(Dispatchers.Default) { runCatching { sso.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) } }
        withContext(Dispatchers.IO) { assertTrue(ssoPosting.await(5, TimeUnit.SECONDS)) }
        val renewal = async(Dispatchers.Default) { runCatching { moodle.renewToken(null) } }

        val signInFailure = signIn.await().exceptionOrNull()
        val renewalFailure = renewal.await().exceptionOrNull()

        assertTrue("got $signInFailure", signInFailure is SsoLoginError.LoginFailed)
        assertTrue("got $renewalFailure", renewalFailure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(listOf("sso"), posts)
        assertTrue(latch.isRejected)
    }

    private class Tokens : MoodleTokenPairStore {
        override var token: String? = null
        override var privateToken: String? = null
        override val studentId: String = STUDENT
        override val password: String = PASSWORD
        override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?) = true.also {
            this.token = token
            this.privateToken = privateToken
        }
    }

    private fun client(answer: (Request) -> Pair<String, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val (url, html) = answer(request)
        Response.Builder()
            // As if OkHttp had followed the redirects to it.
            .request(request.newBuilder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }.build()

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "old-password"
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val REFUSAL = """<div class="validation-summary-errors"><ul><li>Wrong account or password</li></ul></div>"""

        /** How long the SSO post waits for the renewal's: plenty for one that is not held back. */
        const val RACE_WINDOW_MS = 500L

        val SSO_PAGE = """
            <html><body>
            <form id="loginForm" action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-token" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()

        val MOODLE_PAGE = """
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

        val SSO_PAGE_REFUSED = SSO_PAGE.replace("<form", "$REFUSAL<form")
        val MOODLE_PAGE_REFUSED = MOODLE_PAGE.replace("<form", "$REFUSAL<form")
    }
}
