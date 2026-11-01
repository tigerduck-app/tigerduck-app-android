package org.ntust.app.tigerduck.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager

/**
 * The steps of [AppState.performFullReset], apart from it so a test can run
 * them: the sign-outs, then every store wiped, the watch told and the one-shot
 * checks marked done.
 *
 * [CredentialManager.clearAll] and [AppPreferences.clearAllPrefs] both clear
 * their whole store, so the library account and every library preference
 * (flip-to-open consent) go with them; neither needs naming here, and neither
 * should be, since a named list silently stops being complete.
 */
internal class FullReset(
    /** The mail account is separate from the NTUST sign-in (spec §7.5), but a full reset wipes it too. */
    private val signOutMail: () -> Unit,
    private val signOutSchool: () -> Unit,
    private val clearCaches: suspend () -> Unit,
    private val credentials: CredentialManager,
    private val prefs: AppPreferences,
    private val publishLibraryCredentialsToWatch: suspend () -> Unit,
    /** The TigerDuck browser's cookies, storage and lists; see [org.ntust.app.tigerduck.browser.BrowserDataCleaner]. */
    private val clearBrowser: suspend () -> Unit,
) {
    /** Signs out, then wipes the stores in [scope] and runs [onWiped]. */
    fun start(scope: CoroutineScope, onWiped: () -> Unit): Job {
        signOutMail()
        signOutSchool()
        return scope.launch {
            wipeStores()
            onWiped()
        }
    }

    private suspend fun wipeStores() {
        runCatching { clearCaches() }
        runCatching { clearBrowser() }
        credentials.clearAll()
        // Then the watch, so a library account of another student, which the
        // school sign-out before keeps, does not stay on it.
        publishLibraryCredentialsToWatch()
        prefs.clearAllPrefs()
        // Re-stamp the schema so the dialog doesn't re-fire on next launch.
        prefs.dataSchemaVersion = DataMigration.CURRENT_SCHEMA
        // Same for the pre-2.3.0 bottom-bar check: once the user onboards
        // again, the next launch would otherwise pin the old Calendar bar
        // on what is now a fresh start. And for the Library one, which
        // would pin the bar without Library there, as it does for an upgrade.
        prefs.previousDefaultTabsChecked = true
        prefs.libraryTabKeepChecked = true
        // Nothing from before the reset is left for it to read.
        prefs.flipToLibraryDefaultOffChecked = true
        // Nor for the notification settings' upgrade step, which, run again,
        // would read an edit made after the reset, with nothing recorded, as
        // one an earlier build left, and send every field of it once.
        prefs.lastSyncedSettingsSeedDone = true
    }
}
