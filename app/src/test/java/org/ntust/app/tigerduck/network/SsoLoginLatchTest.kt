package org.ntust.app.tigerduck.network

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.auth.RetrySettlement
import org.ntust.app.tigerduck.auth.guardAutomaticHarvest
import org.ntust.app.tigerduck.auth.retryStoredPasswordChain
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import java.util.concurrent.CopyOnWriteArrayList
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [SsoLoginService] against hand-made school pages: an automatic sign-in the school refuses
 * latches, a latched one posts no password, and a sign-in the user types lifts the latch
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/163).
 */
class SsoLoginLatchTest {

    private lateinit var latch: PersistedStoredPasswordLatch
    private lateinit var service: SsoLoginService
    private lateinit var sessionManager: NtustSessionManager
    private var stored: Pair<String, String>? = STUDENT to PASSWORD
    private var clock = 1_800_000_000_000L

    /** Whether the school takes the password posted to it. */
    private var accepts = false

    /** Whether a visit to the service answers without the school's sign-in form. */
    private var visitPassesSso = false

    /** Runs as each visit to the service arrives, with how many came before it. */
    private var onVisit: (Int) -> Unit = {}
    private var visits = 0
    private val posts = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        val context = JvmContext()
        val latchPrefs = context.getSharedPreferences("sso-latch-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
        latch = PersistedStoredPasswordLatch(latchPrefs, now = { clock }) { stored }
        sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val school = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
        service = SsoLoginService(sessionManager, latch) { school }
    }

    @Test
    fun `an automatic sign-in the school refuses latches with its message`() = runTest {
        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(REASON, (failure as SsoLoginError.LoginFailed).reason)
        assertEquals(1, posts.size)
        assertTrue(latch.isRejected)
        assertEquals(REASON, latch.rejection.value?.reason)
    }

    @Test
    fun `a latched automatic sign-in posts no password`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        accepts = true

        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(REASON, (failure as SsoLoginError.LoginFailed).reason)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a refusal latched while an automatic sign-in is on its way to the form stops it`() = runTest {
        accepts = true
        // Another sign-in's refusal lands after this one's first look at the form.
        onVisit = { if (it == 1) latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON) }

        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `an automatic sign-in whose password a sign-in by hand replaced on the way posts nothing`() = runTest {
        accepts = true
        // A sign-in by hand stores a new password, and lifts the latch, after this one began.
        onVisit = {
            if (it == 1) {
                stored = STUDENT to "new-password"
                latch.clear()
            }
        }

        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.AccountChanged)
        assertEquals(emptyList<String>(), posts)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `an automatic sign-in with a password no longer stored posts nothing`() = runTest {
        accepts = true
        // Read before a sign-in by hand stored a new one.
        stored = STUDENT to "new-password"

        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.AccountChanged)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a sign-in the user types posts despite the latch, which lifts only with the new password's store`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        accepts = true

        assertTrue(service.signIn(SERVICE, STUDENT, "new-password"))

        assertEquals(1, posts.size)
        // The refused password is still the one stored: TypedAccountSignIn lifts the latch as
        // it stores the new one.
        assertTrue(latch.isRejected)
    }

    @Test
    fun `a sign-in the user types that the school refuses latches nothing`() = runTest {
        val failure = runCatching { service.signIn(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(REASON, (failure as SsoLoginError.LoginFailed).reason)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `an automatic sign-in the school accepts latches nothing`() = runTest {
        accepts = true

        assertTrue(service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(1, posts.size)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `an automatic sign-in the school accepts once the wait is over ends the refusals`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        clock += 10_000
        accepts = true

        assertTrue(service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(1, posts.size)
        assertEquals(0, latch.refusals.value)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `a sign-in again empties the session before its visit, and leaves the refusals for the retry to settle`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        clock += 10_000
        accepts = true
        sessionManager.markLoginSuccess()
        val sessionAtFirstVisit = mutableListOf<Long>()
        onVisit = { if (it == 0) sessionAtFirstVisit += sessionManager.loginTimestampMs }

        assertTrue(service.signInAgain(SERVICE, STUDENT, PASSWORD) { true })

        assertEquals(listOf(0L), sessionAtFirstVisit)
        assertEquals(1, posts.size)
        // The retry settles the count once the Moodle token step is through as well.
        assertEquals(1, latch.refusals.value)
    }

    @Test
    fun `a sign-in again still waits out a refusal's wait`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        val failure = runCatching { service.signInAgain(SERVICE, STUDENT, PASSWORD) { true } }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(0, posts.size)
    }

    /** Another automatic sign-in that held the lock first, and that the school took, settled it. */
    @Test
    fun `a sign-in again whose refusal is gone once it holds the lock keeps the session and posts nothing`() = runTest {
        sessionManager.markLoginSuccess()
        val session = sessionManager.loginTimestampMs

        assertTrue(service.signInAgain(SERVICE, STUDENT, PASSWORD) { false })

        assertEquals(session, sessionManager.loginTimestampMs)
        assertEquals(0, visits)
        assertEquals(0, posts.size)
    }

    /** Another automatic post was refused, and its wait started, while this one waited for the lock. */
    @Test
    fun `a sign-in again that must wait leaves the warm session as it was`() = runTest {
        sessionManager.markLoginSuccess()
        val session = sessionManager.loginTimestampMs
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        val failure = runCatching { service.signInAgain(SERVICE, STUDENT, PASSWORD) { true } }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(session, sessionManager.loginTimestampMs)
        assertEquals(0, visits)
        assertEquals(0, posts.size)
    }

    /**
     * The school takes every post, and the Moodle token renewal is refused every time: the
     * retry's own yes from the school must not end the count, or each refused renewal starts it
     * again at the first step, and the retry posts every 10 seconds for good.
     */
    @Test
    fun `a retry the school takes but whose Moodle token renewal is refused still backs off`() = runTest {
        accepts = true
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        val gaps = mutableListOf<Long>()

        repeat(4) {
            val due = latch.retryAt()!!
            gaps += due - clock
            clock = due
            val settlement = RetrySettlement(latch, STUDENT, PASSWORD)
            retryStoredPasswordChain(
                signInToSchool = {
                    runCatching {
                        service.signInAgain(SERVICE, STUDENT, PASSWORD, settlement::schoolAccepted) { latch.refusals.value > 0 }
                    }.getOrDefault(false)
                },
                moodleTokenMissing = { true },
                renewMoodleToken = {
                    latch.guardAutomaticHarvest(STUDENT, PASSWORD) { posting ->
                        posting.post<Unit> { throw MoodleWebserviceError.SsoLoginRejected(REASON) }
                    }
                },
                settle = settlement::settle,
                signInToBackend = { true },
            )
        }

        assertEquals(listOf(10_000L, 60_000L, 300_000L, 21_600_000L), gaps)
    }

    @Test
    fun `a retry the school takes, with a Moodle token stored, ends the refusals before the backend`() = runTest {
        accepts = true
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        clock = latch.retryAt()!!
        val settlement = RetrySettlement(latch, STUDENT, PASSWORD)
        var refusalsAtBackend = -1

        assertTrue(
            retryStoredPasswordChain(
                signInToSchool = { service.signInAgain(SERVICE, STUDENT, PASSWORD, settlement::schoolAccepted) { latch.refusals.value > 0 } },
                moodleTokenMissing = { false },
                renewMoodleToken = {},
                settle = settlement::settle,
                signInToBackend = { refusalsAtBackend = latch.refusals.value; true },
            ),
        )

        assertEquals(0, refusalsAtBackend)
        assertFalse(latch.isRejected)
    }

    /** Another automatic post was refused after the school took the retry's, while its token step ran. */
    @Test
    fun `a retry does not settle a refusal counted after the school took its post`() = runTest {
        accepts = true
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        clock = latch.retryAt()!!
        val settlement = RetrySettlement(latch, STUDENT, PASSWORD)

        retryStoredPasswordChain(
            signInToSchool = { service.signInAgain(SERVICE, STUDENT, PASSWORD, settlement::schoolAccepted) { latch.refusals.value > 0 } },
            moodleTokenMissing = { true },
            // The token from a live session, no post; meanwhile another sign-in is refused.
            renewMoodleToken = { latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON) },
            settle = settlement::settle,
            signInToBackend = { true },
        )

        assertTrue(latch.isRejected)
    }

    /** The service let the visit through after the jar was emptied, so no password went out. */
    @Test
    fun `a retry that posted no password settles nothing`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        clock = latch.retryAt()!!
        visitPassesSso = true
        val settlement = RetrySettlement(latch, STUDENT, PASSWORD)

        retryStoredPasswordChain(
            signInToSchool = { service.signInAgain(SERVICE, STUDENT, PASSWORD, settlement::schoolAccepted) { latch.refusals.value > 0 } },
            moodleTokenMissing = { false },
            renewMoodleToken = {},
            settle = settlement::settle,
            signInToBackend = { true },
        )

        assertEquals(0, posts.size)
        assertTrue(latch.isRejected)
    }

    private fun answer(request: Request): Response {
        val (url, html) = when {
            request.method == "POST" -> {
                posts += request.url.toString()
                if (accepts) SERVICE to SERVICE_PAGE else LOGIN_URL to LOGIN_PAGE_REFUSED
            }
            // Every visit to the service lands on the school's sign-in form, unless the service
            // answers without one.
            else -> {
                onVisit(visits++)
                if (visitPassesSso) SERVICE to SERVICE_PAGE else LOGIN_URL to LOGIN_PAGE
            }
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
        const val PASSWORD = "old-password"
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val REASON = "Wrong account or password"

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
            """<div class="validation-summary-errors"><ul><li>$REASON</li></ul></div><form""",
        )

        const val SERVICE_PAGE = "<html><body>Course selection</body></html>"
    }
}
