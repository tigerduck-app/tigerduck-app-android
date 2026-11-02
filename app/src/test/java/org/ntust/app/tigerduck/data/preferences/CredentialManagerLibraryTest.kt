package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Every way the phone removes the library account keeps a library sign-in begun
 * before it from storing that account again
 * (tigerduck-app/tigerduck-app-android#293).
 */
@OptIn(StartsAccountWork::class)
class CredentialManagerLibraryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = AccountSession(SignInSeries(), scope)

    // Plain preferences: the JVM has no Android keystore for the encrypted ones.
    private val prefs = JvmContext().getSharedPreferences("credentials-test", Context.MODE_PRIVATE)
    private val credentials = CredentialManager(prefs, session)

    @After
    fun stopTheScope() = scope.cancel()

    private fun signedIn() {
        assertTrue(save(credentials.libraryAccountGeneration))
    }

    private fun save(generation: Int, byUser: Boolean = false) =
        credentials.saveLibrarySignIn(generation, "B10000001", "pw", "token", Long.MAX_VALUE, byUser)

    private fun assertNoAccount() {
        assertNull(credentials.libraryUsername)
        assertNull(credentials.libraryPassword)
        assertNull(credentials.libraryToken)
    }

    @Test
    fun `a library sign-out ends the library's sign-in, and the next one opens at once`() {
        val before = session.current

        credentials.clearLibraryCredentials(SignOutReason.LIBRARY)

        assertFalse(before.isCurrent)
        assertTrue(session.signIns.isOpen.value)
        assertTrue(session.current.isCurrent)
    }

    @Test
    fun `a full reset ends the library's sign-in too`() {
        val before = session.current

        credentials.clearAll(SignOutReason.FULL_RESET)

        assertFalse(before.isCurrent)
        assertTrue(session.signIns.isOpen.value)
    }

    @Test
    fun `a library sign-out and a full reset are each told, with the sign-in they ended and the reason given`() {
        val told = mutableListOf<AccountEvent>()
        val listening = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { told += it }
        }
        val first = session.current

        credentials.clearLibraryCredentials(SignOutReason.ENDPOINT_CHANGE)
        val second = session.current
        credentials.clearAll(SignOutReason.FULL_RESET)
        listening.cancel()

        assertEquals(
            listOf<AccountEvent>(
                AccountEvent.SignedOut(Account.LIBRARY, SignOutReason.ENDPOINT_CHANGE, first, address = null),
                AccountEvent.SignedOut(Account.LIBRARY, SignOutReason.FULL_RESET, second, address = null),
            ),
            told,
        )
    }

    @Test
    fun `a school sign-out leaves the library's sign-in`() {
        val before = session.current

        credentials.signOutSchoolAccount()

        assertTrue(before.isCurrent)
    }

    @Test
    fun `work on the library session goes with its sign-in, by either way out`() {
        for (signOut in listOf<(SignOutReason) -> Unit>(credentials::clearLibraryCredentials, credentials::clearAll)) {
            val work = session.launch { awaitCancellation() }
            signOut(SignOutReason.LIBRARY)
            runBlocking { withTimeout(5_000) { work.join() } }
            assertTrue(work.isCancelled)
        }
    }

    /** The account comes off with both moved: nothing can find one gone and the other still here. */
    @Test
    fun `the sign-in has ended by the time the account comes off`() {
        val before = session.current
        var endedAtTheRemoval: Boolean? = null
        val watched = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                endedAtTheRemoval = !before.isCurrent
                return prefs.edit()
            }
        }

        CredentialManager(watched, session).clearLibraryCredentials(SignOutReason.LIBRARY)

        assertEquals(true, endedAtTheRemoval)
    }

    @Test
    fun `a sign-in stores the account and its token`() {
        signedIn()
        assertEquals("B10000001", credentials.libraryUsername)
        assertEquals("pw", credentials.libraryPassword)
        assertTrue(credentials.isLibraryTokenValid)
    }

    @Test
    fun `a library sign-out keeps a sign-in begun before it from storing the account`() {
        signedIn()
        val generation = credentials.libraryAccountGeneration

        credentials.clearLibraryCredentials(SignOutReason.LIBRARY)

        assertFalse(save(generation))
        assertFalse(save(generation, byUser = true))
        assertNoAccount()
    }

    @Test
    fun `a full reset keeps a sign-in begun before it from storing the account`() {
        signedIn()
        val generation = credentials.libraryAccountGeneration

        credentials.clearAll(SignOutReason.FULL_RESET)

        assertFalse(save(generation))
        assertNoAccount()
    }
}
