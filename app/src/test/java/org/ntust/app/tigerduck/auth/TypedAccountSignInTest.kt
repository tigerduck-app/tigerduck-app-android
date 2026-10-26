package org.ntust.app.tigerduck.auth

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The sign-in with a password the user typed, against hand-made school pages, while the school's
 * refusal of the stored password is latched: the latch lifts only together with the store of the
 * new password, whenever the sign-in is cancelled, and nothing is signed in to the library: each
 * account signs in by itself.
 */
class TypedAccountSignInTest {

    private lateinit var latch: PersistedStoredPasswordLatch
    private lateinit var school: SsoLoginService
    private var stored: Pair<String, String>? = STUDENT to OLD_PASSWORD

    /** Whether the school takes the password posted to it. */
    private var accepts = true

    /** Runs as the school gets the password, before its answer. */
    private var onPost: () -> Unit = {}

    /** Whether the refusal still held when the new password was stored, once one was. */
    private var latchedAtStore: Boolean? = null

    /** Every school request, as its method. */
    private val requests = mutableListOf<String>()

    /** The steps of the sign-in, in order. */
    private val steps = mutableListOf<String>()

    /** What must hold before the account shows as signed in. */
    private var prepare: suspend () -> Unit = {}

    /** Runs as the demo account signs in on the device. */
    private var onDemo: () -> Unit = {}

    /** The sign-in generation a sign-out moves, as AuthTokenManager's. */
    private var generation = 0
    private val accountLock = Any()
    private var signedIn = false

    /** What AuthService.logout does to what the sign-in reads and writes. */
    private fun signOut() {
        synchronized(accountLock) {
            stored = null
            generation++
        }
        latch.clear()
        signedIn = false
    }

    @Before
    fun setUp() {
        val context = JvmContext()
        val latchPrefs = context.getSharedPreferences("typed-sign-in-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
        latch = PersistedStoredPasswordLatch(latchPrefs, now = { 1_800_000_000_000L }) { stored }
        latch.markRejected(latch.generation, STUDENT, OLD_PASSWORD, "Wrong account or password")
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val client = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
        school = SsoLoginService(sessionManager, latch) { client }
    }

    private fun typedSignIn() = TypedAccountSignIn(
        schoolSignIn = { studentId, password -> school.signIn(SERVICE, studentId, password) },
        signInInteractively = { signIn -> signIn(GENERATION) == SignInAttempt.SIGNED_IN },
        signInGeneration = { generation },
        accountLock = accountLock,
        storedStudentId = { stored?.first },
        writeAccount = { studentId, password ->
            latchedAtStore = latch.rejection.value != null
            stored = studentId to password
            steps += "store"
        },
        latch = latch,
        isDemoAccount = { _, password -> password == DEMO_PASSWORD },
        enterDemo = {
            steps += "demo"
            onDemo()
        },
        accountChanging = { steps += "owed" },
        markSignedIn = {
            signedIn = true
            steps += "shown"
        },
    )

    private suspend fun signIn(password: String = NEW_PASSWORD) = typedSignIn().signIn(
        STUDENT,
        password,
        prepareSignedIn = {
            steps += "prepare"
            prepare()
        },
    ) { _, _ ->
        steps += "rest"
        SignInAttempt.SIGNED_IN
    }

    @Test
    fun `the refusal holds until the new password is stored, and lifts with it`() = runTest {
        val outcome = signIn()

        assertEquals(TypedAccountSignIn.Outcome.SIGNED_IN, outcome)
        assertEquals("nothing lifted it before the new password was stored", true, latchedAtStore)
        assertEquals(STUDENT to NEW_PASSWORD, stored)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `a sign-in cancelled after the school took the password keeps it, with no refusal held`() = runTest {
        val prepareStarted = CompletableDeferred<Unit>()
        // The step before the account shows, which the user leaves before it is done.
        prepare = {
            prepareStarted.complete(Unit)
            awaitCancellation()
        }
        val job = launch { signIn() }
        prepareStarted.await()

        job.cancelAndJoin()

        assertEquals(STUDENT to NEW_PASSWORD, stored)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `a sign-in cancelled while the school answers keeps the old password and its refusal`() = runTest {
        lateinit var job: Job
        // The user leaves while the school is still answering.
        onPost = { job.cancel() }
        job = launch { signIn() }

        job.join()

        assertEquals(STUDENT to OLD_PASSWORD, stored)
        assertNotNull(latch.rejection.value)
        assertTrue(latch.isRejected)
    }

    @Test
    fun `a sign-in the school refuses stores nothing and keeps the refusal`() = runTest {
        accepts = false

        val failure = runCatching { signIn() }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(STUDENT to OLD_PASSWORD, stored)
        assertTrue(latch.isRejected)
        assertEquals("no account change, so no clearing owed", emptyList<String>(), steps)
    }

    @Test
    fun `a school sign-in signs nothing in to the library, and shows the account after the store`() = runTest {
        signIn()

        // The browser's clearing is owed before the new account reaches disk.
        assertEquals(listOf("owed", "store", "prepare", "shown", "rest"), steps)
    }

    @Test
    fun `the demo account signs in on the device, and its store lifts the refusal too`() = runTest {
        val outcome = signIn(password = DEMO_PASSWORD)

        assertEquals(TypedAccountSignIn.Outcome.DEMO, outcome)
        assertEquals(emptyList<String>(), requests)
        assertEquals(STUDENT to DEMO_PASSWORD, stored)
        assertFalse(latch.isRejected)
        // Owed before demo mode, which is on disk too, goes on.
        assertEquals(listOf("owed", "demo", "store", "prepare", "shown"), steps)
    }

    @Test
    fun `a sign-out as the school answers keeps the sign-in from storing anything`() = runTest {
        // logout() takes no sign-in lock, so it can land between the school's answer and the store.
        onPost = { signOut() }

        val outcome = signIn()

        assertEquals(TypedAccountSignIn.Outcome.NOT_SIGNED_IN, outcome)
        assertEquals(null, stored)
        assertEquals(emptyList<String>(), steps)
        assertFalse(signedIn)
    }

    @Test
    fun `a sign-out before the account shows keeps it from showing as signed in`() = runTest {
        prepare = { signOut() }

        val outcome = signIn()

        assertEquals(TypedAccountSignIn.Outcome.NOT_SIGNED_IN, outcome)
        assertEquals(null, stored)
        assertEquals(listOf("owed", "store", "prepare"), steps)
        assertFalse(signedIn)
    }

    @Test
    fun `a sign-out while the demo account signs in stores nothing`() = runTest {
        onDemo = { signOut() }

        val outcome = signIn(password = DEMO_PASSWORD)

        assertEquals(TypedAccountSignIn.Outcome.NOT_SIGNED_IN, outcome)
        assertEquals(null, stored)
        assertFalse(signedIn)
    }

    private fun answer(request: Request): Response {
        requests += request.method
        val (url, html) = if (request.method == "POST") {
            onPost()
            if (accepts) SERVICE to SERVICE_PAGE else LOGIN_URL to LOGIN_PAGE_REFUSED
        } else {
            // Every visit to the service lands on the school's sign-in form.
            LOGIN_URL to LOGIN_PAGE
        }
        return Response.Builder()
            // As if OkHttp had followed the redirect to it.
            .request(request.newBuilder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val OLD_PASSWORD = "old-password"
        const val NEW_PASSWORD = "new-password"
        const val DEMO_PASSWORD = "made-up-demo-password"
        const val GENERATION = 7
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"

        val LOGIN_PAGE = """
            <html><body>
            <form id="loginForm" action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-token" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()

        val LOGIN_PAGE_REFUSED = LOGIN_PAGE.replace(
            "<form",
            """<div class="validation-summary-errors"><ul><li>Wrong account or password</li></ul></div><form""",
        )

        const val SERVICE_PAGE = "<html><body>Course selection</body></html>"
    }
}
