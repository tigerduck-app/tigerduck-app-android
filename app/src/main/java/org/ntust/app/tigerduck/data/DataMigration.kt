package org.ntust.app.tigerduck.data

import android.content.Context
import android.util.Log
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager

/**
 * One-shot runner for on-device data migrations.
 *
 * User settings live in three places that survive every upgrade untouched:
 *   - plain SharedPreferences ([AppPreferences]) for UI prefs & home layout
 *   - EncryptedSharedPreferences ([CredentialManager]) for NTUST / library logins
 *   - JSON files on disk ([org.ntust.app.tigerduck.data.cache.DataCache])
 *     for cached courses, assignments, calendar events, scores, etc.
 *
 * This runner handles the two cases where an upgrade can't be silent:
 *   1. Removed features leave orphaned files (e.g. the `tigerduck.db` from
 *      the now-deleted Room layer) — those get cleaned up.
 *   2. The stored version is outside what this build can migrate (corrupt
 *      credential store, user downgraded the app, old-and-unsupported
 *      layout) — [run] returns [Outcome.NeedsUserReset] and the UI is
 *      expected to show a "please re-login and reconfigure" prompt.
 */
class DataMigration(
    private val context: Context,
    private val prefs: AppPreferences,
    private val credentials: CredentialManager,
) {
    enum class Outcome {
        /** No migration needed, or all pending steps applied successfully. */
        Ok,

        /** Stored data cannot be migrated; UI must prompt the user to reset. */
        NeedsUserReset,
    }

    fun run(): Outcome {
        // If Keystore corruption forced CredentialManager to rebuild the
        // credential store from scratch, the user's logins are gone and
        // the app is effectively logged out. Surface it so the dialog
        // fires even though SharedPreferences look fine.
        if (credentials.wasRecreatedDueToCorruption) {
            prefs.dataSchemaVersion = CURRENT_SCHEMA
            Log.w(TAG, "Credential store was recreated; prompting for reset")
            return Outcome.NeedsUserReset
        }

        val stored = prefs.dataSchemaVersion
        if (stored > CURRENT_SCHEMA) {
            // User downgraded the app. Forward-written prefs may contain
            // keys/values this build doesn't understand.
            Log.w(TAG, "Stored schema $stored is newer than CURRENT_SCHEMA $CURRENT_SCHEMA")
            return Outcome.NeedsUserReset
        }
        if (stored < MIN_SUPPORTED_SCHEMA) {
            Log.w(TAG, "Stored schema $stored is below MIN_SUPPORTED_SCHEMA $MIN_SUPPORTED_SCHEMA")
            return Outcome.NeedsUserReset
        }

        var current = stored
        while (current < CURRENT_SCHEMA) {
            when (current) {
                0 -> migrate0to1()
            }
            current++
            prefs.dataSchemaVersion = current
        }
        return Outcome.Ok
    }

    private fun migrate0to1() {
        // Pre-1.1.6 builds shipped a Room database that nothing read or wrote.
        // It's a few empty tables in the app's private storage — drop it so
        // the upgrade leaves no trace.
        runCatching { context.deleteDatabase("tigerduck.db") }
            .onFailure { Log.w(TAG, "Failed to delete orphaned tigerduck.db", it) }
    }

    companion object {
        private const val TAG = "DataMigration"

        /** Highest schema this build writes. Bump when adding a new step. */
        const val CURRENT_SCHEMA = 1

        /**
         * Lowest schema this build can migrate forward from. Anything below
         * this triggers [Outcome.NeedsUserReset]. Fresh installs come in at
         * 0 (the default), so keep this at 0 unless you intentionally want
         * to force all old installs to reset.
         */
        const val MIN_SUPPORTED_SCHEMA = 0
    }
}
