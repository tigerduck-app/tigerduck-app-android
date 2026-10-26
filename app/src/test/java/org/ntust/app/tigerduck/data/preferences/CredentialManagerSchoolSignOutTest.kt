package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.library.FakeLibraryServer
import org.ntust.app.tigerduck.shared.LibraryServiceError
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A school sign-out takes the school account alone: the library account stays signed in, whoever's
 * it is, as each account signs in and out by itself (decided for Android on 2026-10-22).
 */
class CredentialManagerSchoolSignOutTest {

    // Plain preferences: the JVM has no Android keystore for the encrypted ones.
    private val prefs = JvmContext()
        .getSharedPreferences("credentials-school-sign-out-test", Context.MODE_PRIVATE)
    private lateinit var credentials: CredentialManager

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        credentials = CredentialManager(prefs)
    }

    private fun libraryAccount(username: String) {
        assertTrue(
            credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, username, "pw", "token", Long.MAX_VALUE, byUser = true),
        )
    }

    @Test
    fun `the same student's library account stays through the school sign-out`() {
        credentials.ntustStudentId = "B10000001"
        credentials.ntustPassword = "school"
        libraryAccount("B10000001")

        credentials.signOutSchoolAccount()

        assertNull(credentials.ntustStudentId)
        assertNull(credentials.ntustPassword)
        assertEquals("B10000001", credentials.libraryUsername)
        assertEquals("pw", credentials.libraryPassword)
        assertTrue(credentials.isLibraryTokenValid)
    }

    @Test
    fun `another student's library account stays through the school sign-out`() {
        credentials.ntustStudentId = "B10000001"
        credentials.ntustPassword = "school"
        libraryAccount("B10000009")

        credentials.signOutSchoolAccount()

        assertNull(credentials.ntustStudentId)
        assertEquals("B10000009", credentials.libraryUsername)
    }

    @Test
    fun `the school sign-out moves the school account, and only it`() {
        credentials.ntustStudentId = "B10000001"
        libraryAccount("B10000001")
        val school = credentials.schoolAccountGeneration
        val library = credentials.libraryAccountGeneration

        credentials.signOutSchoolAccount()

        assertFalse(credentials.isSchoolAccount(school))
        assertEquals(library, credentials.libraryAccountGeneration)
    }

    /**
     * The library account, signing in again with its stored credentials while
     * the school account of the same student signs out: the sign-out keeps
     * that account, so what the library answers is still about its password.
     */
    private fun storedSignInDuringSchoolSignOut(): FakeLibraryServer {
        credentials.ntustStudentId = "B10000001"
        assertTrue(
            credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "pw", "expired", 1L, byUser = true),
        )
        return FakeLibraryServer().apply { whileSigningIn = { credentials.signOutSchoolAccount() } }
    }

    @Test
    fun `a refusal found as the school account signs out is kept`() = runBlocking {
        val server = storedSignInDuringSchoolSignOut()
        server.signInAnswers += FakeLibraryServer.WRONG_PASSWORD
        val service = server.service(credentials)

        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.LoginFailed)

        assertTrue(credentials.libraryPasswordRefused)
        // Each refusal counts toward the account's lockout: the password waits before it goes again.
        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.CredentialsNotFound)
        assertFalse(service.signInIfCredentialed { true })
        assertEquals(1, server.signIns)
    }

    @Test
    fun `a token renewed as the school account signs out is kept`() = runBlocking {
        val service = storedSignInDuringSchoolSignOut().service(credentials)

        assertEquals("token-1", service.ensureToken())

        assertEquals("B10000001", credentials.libraryUsername)
        assertEquals("token-1", credentials.libraryToken)
        assertTrue(credentials.isLibraryTokenValid)
    }
}
