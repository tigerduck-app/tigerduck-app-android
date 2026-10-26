package org.ntust.app.tigerduck.ui

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.library.libraryCredentialsForWatch
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The full reset [AppState.performFullReset] runs: it leaves no library
 * account on the watch, another student's included, which the school
 * sign-out it begins with keeps.
 */
class FullResetTest {

    private val context = JvmContext()

    // Plain preferences: the JVM has no Android keystore for the encrypted ones.
    private val credentialPrefs = context.getSharedPreferences("credentials-reset-test", Context.MODE_PRIVATE)
    private lateinit var credentials: CredentialManager
    private val prefs = AppPreferences(context)

    /** What the watch was handed, in order: user name to password, or null for none. */
    private val watch = mutableListOf<Pair<String, String>?>()
    private val steps = mutableListOf<String>()

    @Before
    fun setUp() {
        credentialPrefs.edit().clear().commit()
        credentials = CredentialManager(credentialPrefs)
        credentials.ntustStudentId = "B10000001"
        credentials.ntustPassword = "school"
        // A library account, which a school sign-out keeps.
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000009", "pw", "token", Long.MAX_VALUE, byUser = true)
    }

    private fun fullReset() = FullReset(
        signOutMail = { steps += "mail" },
        // As AuthService.logout, after which the app hands the watch the library account again.
        signOutSchool = {
            steps += "school"
            credentials.signOutSchoolAccount()
            watch += libraryCredentialsForWatch(credentials)
        },
        clearCaches = { steps += "caches" },
        credentials = credentials,
        prefs = prefs,
        publishLibraryCredentialsToWatch = { watch += libraryCredentialsForWatch(credentials) },
        clearBrowser = { steps += "browser" },
    )

    @Test
    fun `a full reset leaves no library account on the watch`() = runBlocking {
        var wiped = false

        fullReset().start(this) { wiped = true }.join()

        assertTrue(wiped)
        assertEquals(listOf("mail", "school", "caches", "browser"), steps)
        assertEquals("B10000009" to "pw", watch.first())
        assertNull(watch.last())
        assertNull(credentials.libraryUsername)
        assertNull(credentials.ntustStudentId)
    }

    @Test
    fun `a full reset marks the one-shot checks done for the fresh start`() = runBlocking {
        prefs.flipToLibraryDefaultOffChecked = false

        fullReset().start(this) {}.join()

        assertEquals(DataMigration.CURRENT_SCHEMA, prefs.dataSchemaVersion)
        assertTrue(prefs.previousDefaultTabsChecked)
        assertTrue(prefs.libraryTabKeepChecked)
        assertTrue(prefs.flipToLibraryDefaultOffChecked)
    }
}
