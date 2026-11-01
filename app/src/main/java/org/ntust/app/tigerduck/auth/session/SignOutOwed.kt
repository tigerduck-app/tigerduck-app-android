package org.ntust.app.tigerduck.auth.session

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Whether a sign-out's wipe of the school account's data is still owed, kept on disk so a wipe a
 * process death cut short is finished at the next start (as [org.ntust.app.tigerduck.browser.BrowserCleanup]
 * does for the in-app browser). Set before the sign-in ends, cleared only when the session opens
 * again, after the wipe ran. While it is set the school session stays closed, so nothing can
 * write and a sign-in waits.
 *
 * Device scope: the marker is about the device's files, not about an account.
 */
class SignOutOwed(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    /** Written to disk before a set returns: the sign-out it precedes may be the last thing the process does. */
    var owed: Boolean
        get() = prefs.getBoolean(KEY_OWED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OWED, value).commit()
        }

    private companion object {
        const val PREFS_FILE = "tigerduck_account_wipe"
        const val KEY_OWED = "owed"
    }
}
