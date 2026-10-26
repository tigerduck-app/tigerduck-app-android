package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Every way the phone removes the library account keeps a library sign-in begun
 * before it from storing that account again
 * (tigerduck-app/tigerduck-app-android#293).
 */
class CredentialManagerLibraryTest {

    // Plain preferences: the JVM has no Android keystore for the encrypted ones.
    private val credentials = CredentialManager(
        JvmContext().getSharedPreferences("credentials-test", Context.MODE_PRIVATE),
    )

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

        credentials.clearLibraryCredentials()

        assertFalse(save(generation))
        assertFalse(save(generation, byUser = true))
        assertNoAccount()
    }

    @Test
    fun `a full reset keeps a sign-in begun before it from storing the account`() {
        signedIn()
        val generation = credentials.libraryAccountGeneration

        credentials.clearAll()

        assertFalse(save(generation))
        assertNoAccount()
    }
}
