package org.ntust.app.tigerduck.auth.session

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.auth.TypedAccountSignIn
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The session around a [SignInSeries]: the work it launches ends with the sign-in, the school
 * session follows [AuthTokenManager.logout], and a typed sign-in waits for the clearing a
 * sign-out leaves behind.
 */
@OptIn(StartsAccountWork::class)
class AccountSessionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private fun session() = AccountSession(SignInSeries(), scope)

    @Test
    fun `launched work is cancelled at the end of the sign-in, and work on another session is not`() = runBlocking {
        val school = session()
        val mail = session()
        val started = CompletableDeferred<Unit>()
        val mailStarted = CompletableDeferred<Unit>()
        var schoolSignIn: SignIn? = null
        val schoolJob = school.launch { signIn ->
            schoolSignIn = signIn
            started.complete(Unit)
            awaitCancellation()
        }
        val mailJob = mail.launch { mailStarted.complete(Unit); awaitCancellation() }
        withTimeout(5_000) { started.await(); mailStarted.await() }

        school.signIns.end()
        school.cancelWork()
        withTimeout(5_000) { schoolJob.join() }

        assertTrue(schoolJob.isCancelled)
        assertFalse(schoolSignIn!!.isCurrent)
        assertTrue("the mail session's work goes on", mailJob.isActive)
        assertTrue(mail.current.isCurrent)
        mailJob.cancelAndJoin()
    }

    @Test
    fun `work launched while the session is closed is cancelled at once`() = runBlocking {
        val school = session()
        school.signIns.end()
        var ran = false

        val job = school.launch { ran = true }
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(ran)
    }

    @Test
    fun `work under a sign-in that has ended is cancelled at once, with the next one open`() = runBlocking {
        val school = session()
        val before = school.current
        school.signIns.end()
        school.open()
        var ran = false

        val job = school.launch(before) { ran = true }
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(ran)
    }

    @Test
    fun `a write under the ended sign-in during the clearing is dropped, even after the session opens`() {
        val school = session()
        school.signIns.end()
        val inTheGap = school.current

        assertNull(inTheGap.ifCurrent { "written" })
        school.open()
        assertNull(inTheGap.ifCurrent { "written" })
        assertEquals("written", school.current.ifCurrent { "written" })
    }

    @Test
    fun `withSignIn hands the current sign-in to the work`() = runBlocking {
        val school = session()
        val seen = school.withSignIn { it }
        assertTrue(seen === school.current)
    }

    @Test
    fun `the token manager's logout ends the school sign-in and cancels its work`() = runBlocking {
        val context = JvmContext()
        val prefs = AppPreferences(context)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-session-test", Context.MODE_PRIVATE))
        tokens.save("access-a", "refresh-a", Long.MAX_VALUE)
        val school = session()
        val manager = AuthTokenManager(tokens, ScriptedBackend().client, prefs, "device-1", session = school)
        val started = CompletableDeferred<Unit>()
        val job = school.launch { started.complete(Unit); awaitCancellation() }
        withTimeout(5_000) { started.await() }
        val before = school.current

        manager.logout()
        withTimeout(5_000) { job.join() }

        assertFalse(before.isCurrent)
        assertFalse("closed until the wipe is done", school.signIns.isOpen.value)
        assertTrue(job.isCancelled)
        school.open()
        assertTrue(school.current.isCurrent)
    }

    // ---- a typed sign-in and the clearing ----

    private var stored: Pair<String, String>? = null

    private var shown = false

    private fun typedSignIn(
        session: AccountSession,
        schoolAnswering: () -> Unit = {},
        currentSignIn: () -> SignIn = { session.signIns.current },
    ) = TypedAccountSignIn(
        schoolSignIn = { _, _ ->
            schoolAnswering()
            true
        },
        signInInteractively = { signIn -> signIn(session.signIns.current) == SignInAttempt.SIGNED_IN },
        awaitOpen = session::awaitOpen,
        currentSignIn = currentSignIn,
        accountLock = Any(),
        storedStudentId = { stored?.first },
        writeAccount = { studentId, password -> stored = studentId to password },
        latch = InMemoryStoredPasswordLatch(),
        leaveStoredAccount = {},
        isDemoAccount = { _, _ -> false },
        enterDemo = { true },
        accountChanging = {},
        markSignedIn = { signIn -> signIn.isCurrent.also { if (it) shown = true } },
    )

    private suspend fun TypedAccountSignIn.typed(prepareSignedIn: suspend () -> Unit = {}) =
        signIn(STUDENT, "made-up-password", { prepareSignedIn() }) { _, _ -> SignInAttempt.SIGNED_IN }

    @Test
    fun `a sign-in during the clearing waits, then signs in`() = runBlocking {
        val school = session()
        school.signIns.end()
        val outcome = scope.async { typedSignIn(school).typed() }

        // Nothing stored while the wipe could still take it.
        delay(200)
        assertNull(stored)
        assertFalse(outcome.isCompleted)

        school.open()

        assertEquals(TypedAccountSignIn.Outcome.SIGNED_IN, withTimeout(5_000) { outcome.await() })
        assertEquals(STUDENT to "made-up-password", stored)
    }

    @Test
    fun `a sign-out landing just after the wait is waited out too`() = runBlocking {
        // The session is open when the sign-in looks, and a sign-out lands before it reads the
        // sign-in: what it reads is the ended one, and asking it shows the sign-out.
        val school = session()
        var signedOut = false
        val outcome = scope.async {
            typedSignIn(
                school,
                currentSignIn = {
                    if (!signedOut) {
                        school.signIns.end()
                        signedOut = true
                    }
                    school.signIns.current
                },
            ).typed()
        }

        delay(200)
        assertNull("stored during the wipe, the account would go with it", stored)
        assertFalse(outcome.isCompleted)

        school.open()
        assertEquals(TypedAccountSignIn.Outcome.SIGNED_IN, withTimeout(5_000) { outcome.await() })
        assertEquals(STUDENT to "made-up-password", stored)
    }

    @Test
    fun `a reply landing after a sign-out began stores nothing`() = runBlocking {
        val school = session()

        val outcome = typedSignIn(school, schoolAnswering = { school.signIns.end() }).typed()

        assertEquals(TypedAccountSignIn.Outcome.NOT_SIGNED_IN, outcome)
        assertNull(stored)
        assertFalse(shown)
    }

    @Test
    fun `a sign-out begun after the store keeps the account from showing as signed in`() = runBlocking {
        val school = session()

        val outcome = typedSignIn(school).typed(prepareSignedIn = { school.signIns.end() })

        assertEquals(TypedAccountSignIn.Outcome.NOT_SIGNED_IN, outcome)
        assertFalse(shown)
    }

    @Test
    fun `a cancelled wait for the clearing leaves the next sign-in free`() = runBlocking {
        val school = session()
        school.signIns.end()
        val waiting = scope.launch { typedSignIn(school).typed() }
        delay(100)

        waiting.cancelAndJoin()
        assertNull(stored)
        school.open()

        assertEquals(TypedAccountSignIn.Outcome.SIGNED_IN, withTimeout(5_000) { typedSignIn(school).typed() })
        assertEquals(STUDENT to "made-up-password", stored)
    }

    private companion object {
        const val STUDENT = "B00015999"
    }
}
