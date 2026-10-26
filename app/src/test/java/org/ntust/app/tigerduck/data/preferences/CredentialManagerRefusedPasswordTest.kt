package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The library's refusals of the stored password, counted with the time of the
 * last, are kept across launches, so the app waits the schedule before it sends
 * the password again by itself, until credentials that work are saved or the
 * library account signs out.
 */
class CredentialManagerRefusedPasswordTest {

    // Plain preferences: the JVM has no Android keystore for the encrypted ones.
    private val prefs = JvmContext()
        .getSharedPreferences("credentials-refused-test", Context.MODE_PRIVATE)
    private lateinit var credentials: CredentialManager

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        credentials = CredentialManager(prefs)
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "pw", "token", 1L, byUser = true)
    }

    @Test
    fun `refusals are counted, with the time of the last, and kept across launches`() {
        assertTrue(credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw", at = 5_000L))
        assertTrue(credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw", at = 9_000L))

        val relaunched = CredentialManager(prefs)
        assertEquals(2, relaunched.libraryPasswordRefusals)
        assertEquals(9_000L, relaunched.libraryPasswordRefusedAt)
        assertTrue(relaunched.libraryPasswordRefused)
    }

    @Test
    fun `credentials saved by a sign-in that works clear it`() {
        credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw", at = 5_000L)

        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "new", "token", Long.MAX_VALUE, byUser = true)

        assertFalse(credentials.libraryPasswordRefused)
        assertEquals(0, CredentialManager(prefs).libraryPasswordRefusals)
        assertEquals(0L, credentials.libraryPasswordRefusedAt)
    }

    @Test
    fun `a library sign-out clears it`() {
        credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw", at = 5_000L)

        credentials.clearLibraryCredentials()

        assertFalse(credentials.libraryPasswordRefused)
        assertEquals(0, CredentialManager(prefs).libraryPasswordRefusals)
    }

    @Test
    fun `a refusal of a password no longer stored, or of an account that left, is not kept`() {
        val generation = credentials.libraryAccountGeneration
        assertFalse(credentials.refuseLibraryPassword(generation, "old", at = 5_000L))

        credentials.clearLibraryCredentials()
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "pw", "token", 1L, byUser = true)
        assertFalse(credentials.refuseLibraryPassword(generation, "pw", at = 5_000L))
        assertFalse(credentials.libraryPasswordRefused)
    }

    @Test
    fun `a refusal kept by a build before the retries gets one try at once, then one every 6 hours`() {
        prefs.edit().putBoolean("library_password_refused", true).commit()

        val upgraded = CredentialManager(prefs)

        assertEquals(StoredPasswordSchedule.quickRetries, upgraded.libraryPasswordRefusals)
        assertEquals(0L, upgraded.libraryPasswordRefusedAt)
        assertFalse(prefs.contains("library_password_refused"))
        // Due at once, and the refusal after it waits the slow step.
        val now = 1_000_000_000L
        assertTrue(StoredPasswordSchedule.retryAt(upgraded.libraryPasswordRefusals, upgraded.libraryPasswordRefusedAt, now)!! <= now)
        assertEquals(6 * 60 * 60_000L, StoredPasswordSchedule.delayMs(upgraded.libraryPasswordRefusals + 1))
    }

    @Test
    fun `a build before the retries that cleared its refusal leaves no count`() {
        prefs.edit().putBoolean("library_password_refused", false).commit()

        assertEquals(0, CredentialManager(prefs).libraryPasswordRefusals)
    }
}
