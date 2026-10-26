package org.ntust.app.tigerduck.ui.screen.signin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sign in again" signs in as the stored account only: another student would inherit what the
 * stored one left on the device, so that one signs out in Settings first.
 */
class SignInAgainAccountTest {

    @Test
    fun `the stored account may sign in again, however it is typed`() {
        assertTrue(signsInAsStoredAccount(typed = " b11300001 ", stored = "B11300001"))
    }

    @Test
    fun `another student may not`() {
        assertFalse(signsInAsStoredAccount(typed = "B11300002", stored = "B11300001"))
    }

    @Test
    fun `with no account stored, anyone may`() {
        assertTrue(signsInAsStoredAccount(typed = "B11300002", stored = null))
    }

    // The sheet's sign-in, as NtustSignInViewModel.signIn runs it

    private val logins = mutableListOf<String>()
    private val login: suspend (String, String) -> Boolean = { studentId, _ -> logins += studentId; true }

    @Test
    fun `the sheet signs in the stored account`() = runTest {
        assertTrue(signInAsStoredAccount("b11300001", "password", stored = "B11300001", login = login))
        assertEquals(listOf("b11300001"), logins)
    }

    @Test
    fun `the sheet sends another student nowhere`() = runTest {
        assertFalse(signInAsStoredAccount("B11300002", "password", stored = "B11300001", login = login))
        assertEquals(emptyList<String>(), logins)
    }

    @Test
    fun `the sheet signs in anyone with no account stored`() = runTest {
        assertTrue(signInAsStoredAccount("B11300002", "password", stored = null, login = login))
        assertEquals(listOf("B11300002"), logins)
    }
}
