package org.ntust.app.tigerduck.ui

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.AccountPlan
import org.ntust.app.tigerduck.auth.session.NoSchool
import org.ntust.app.tigerduck.auth.session.SchoolPlan
import org.ntust.app.tigerduck.auth.session.SignOutPlans
import org.ntust.app.tigerduck.auth.session.SignOutReason
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

    /** The reset's sign-out, through the plans, over accounts that say when they go. */
    private val plans = SignOutPlans(
        // As the school's sign-out, after which the app hands the watch the library account again.
        school = object : SchoolPlan by NoSchool {
            override fun signOut(reason: SignOutReason) {
                steps += "school"
                credentials.signOutSchoolAccount()
                watch += libraryCredentialsForWatch(credentials)
            }
        },
        mail = AccountPlan({ true }) { steps += "mail" },
        library = AccountPlan({ true }) { steps += "library" },
        readLibraryAgain = {},
        leaveDemoLibrary = {},
    )

    private fun fullReset() = FullReset(
        signOut = { plans.signOut(SignOutReason.FULL_RESET) },
        wiped = plans::resetWiped,
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
    fun `a reset whose wipe throws still lets the school's session open`() = runBlocking {
        var released = false
        val quiet = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val plans = SignOutPlans(
            school = object : SchoolPlan by NoSchool {
                override fun releaseClosed() {
                    released = true
                }
            },
            mail = AccountPlan({ false }) {},
            library = AccountPlan({ false }) {},
            readLibraryAgain = {},
            leaveDemoLibrary = {},
        )

        FullReset(
            signOut = { plans.signOut(SignOutReason.FULL_RESET) },
            wiped = plans::resetWiped,
            clearCaches = {},
            credentials = credentials,
            prefs = prefs,
            publishLibraryCredentialsToWatch = { throw IllegalStateException("made-up failure") },
            clearBrowser = {},
        ).start(quiet) {}.join()

        assertTrue(released)
        quiet.cancel()
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

    @Test
    fun `a full reset keeps the notification settings upgrade step done`() = runBlocking {
        // A device that ran the step at the update. Run again after the reset,
        // it would read an edit made then, with nothing recorded, as one an
        // earlier build left, and send every field of it once.
        prefs.lastSyncedSettingsSeedDone = true

        fullReset().start(this) {}.join()

        assertTrue(prefs.lastSyncedSettingsSeedDone)
    }
}
