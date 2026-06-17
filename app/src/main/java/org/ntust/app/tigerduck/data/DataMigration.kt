package org.ntust.app.tigerduck.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
 *
 * [run] is called from [org.ntust.app.tigerduck.TigerDuckApp.onCreate] so
 * migrations complete before any component (BootReceiver, WearScheduleBridge,
 * AppState) reads [org.ntust.app.tigerduck.data.cache.DataCache]. The result
 * is cached; subsequent calls return the same outcome without re-running.
 */
@Singleton
class DataMigration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val credentials: CredentialManager,
) {
    enum class Outcome {
        /** No migration needed, or all pending steps applied successfully. */
        Ok,

        /** Stored data cannot be migrated; UI must prompt the user to reset. */
        NeedsUserReset,
    }

    private val outcome: Outcome by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { doRun() }

    fun run(): Outcome = outcome

    private fun doRun(): Outcome {
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
                1 -> migrate1to2()
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

    /**
     * v1.4.0 shipped without a keep rule for the moved `:shared.Course`
     * class, so R8 renamed its JVM fields. Any `courses_*.json` /
     * `manual_courses_*.json` / legacy `courses.json` saved by that build
     * has obfuscated keys (e.g. `{"a":"...","b":"..."}`) which v1.4.1
     * deserializes into null-field Course objects — callers then NPE the
     * first time they read `courseNo` or `displayName`.
     *
     * Detect by content rather than by file presence: if the JSON contains
     * `"courseNo"` it was written by a build with un-obfuscated field
     * names (any v1.3.x, or v1.4.1+), and we keep it. Otherwise wipe so
     * the next sync repopulates from network with the correct schema.
     * This avoids destroying v1.3.x → v1.4.1 direct upgraders' manual
     * courses, which still have the original keys on disk.
     */
    private fun migrate1to2() {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
        val userDataDir = File(context.filesDir, USER_DATA_SUBDIR)
        sweepCourseFiles(cacheDir) { name ->
            name == LEGACY_COURSES_FILENAME ||
                    (name.startsWith(COURSES_PREFIX) && name.endsWith(".json"))
        }
        sweepCourseFiles(userDataDir) { name ->
            name.startsWith(MANUAL_COURSES_PREFIX) && name.endsWith(".json")
        }
    }

    companion object {
        private const val TAG = "DataMigration"

        // In the companion (not an instance method) so the sentinel sweep —
        // the recovery path for the v1.4.0 upgrade crash — is unit-testable
        // against a temp directory without constructing a Context.
        internal fun sweepCourseFiles(dir: File, accept: (String) -> Boolean) {
            if (!dir.isDirectory) return
            dir.listFiles()
                ?.filter { it.isFile && accept(it.name) }
                ?.forEach { file ->
                    runCatching {
                        if (!file.readText().contains(COURSE_NO_TOKEN)) {
                            if (file.delete()) {
                                Log.i(TAG, "Wiped obfuscated v1.4.0 cache: ${file.name}")
                            }
                        }
                    }.onFailure { Log.w(TAG, "Failed to inspect ${file.name}", it) }
                }
        }

        // Mirrors DataCache constants. Kept in sync deliberately so
        // DataMigration doesn't depend on the DataCache class.
        private const val CACHE_SUBDIR = "TigerDuckCache"
        private const val USER_DATA_SUBDIR = "TigerDuckData"
        private const val LEGACY_COURSES_FILENAME = "courses.json"
        private const val COURSES_PREFIX = "courses_"
        private const val MANUAL_COURSES_PREFIX = "manual_courses_"

        // Presence of this literal in the raw file means the writer used
        // un-obfuscated field names. Absence means an R8-obfuscated v1.4.0
        // writer or some other corrupt state — either way, drop it.
        // Trailing `:` anchors the match to an object key — payload
        // string values cannot legitimately contain an unescaped
        // quote+colon pair, so they can't masquerade as un-obfuscated.
        private const val COURSE_NO_TOKEN = "\"courseNo\":"

        /** Highest schema this build writes. Bump when adding a new step. */
        const val CURRENT_SCHEMA = 2

        /**
         * Lowest schema this build can migrate forward from. Anything below
         * this triggers [Outcome.NeedsUserReset]. Fresh installs come in at
         * 0 (the default), so keep this at 0 unless you intentionally want
         * to force all old installs to reset.
         */
        const val MIN_SUPPORTED_SCHEMA = 0
    }
}
