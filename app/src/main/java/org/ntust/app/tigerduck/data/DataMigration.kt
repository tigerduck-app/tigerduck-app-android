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
 * [run] is called from [org.ntust.app.tigerduck.TigerDuckApp.onCreate], so
 * migrations complete before anything can read or write
 * [org.ntust.app.tigerduck.data.cache.DataCache] — including entry points that
 * never create an Activity, such as `BackgroundSyncWorker`, `BootReceiver` and
 * `WearScheduleBridge`. Running it from `AppState` alone was not enough:
 * WorkManager persists its periodic request across an upgrade and can fire
 * before the user first opens the app, so a cache-clearing step could delete
 * data a sync had just correctly rebuilt.
 *
 * The outcome is cached, so the later call from `AppState` — which needs it to
 * decide whether to show the reset prompt — reuses this result rather than
 * re-running the steps. `performFullReset` clears its own UI flag and re-stamps
 * the schema version, so the stale cached value cannot re-fire the prompt.
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

    /**
     * Runs every pending step once per process and caches the verdict.
     *
     * Synchronous on purpose. The steady-state path is a single
     * `SharedPreferences.getInt` and an early return — file I/O happens only
     * on the one launch that actually migrates, which is the launch that must
     * not be raced.
     */
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
                2 -> migrate2to3()
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

    /**
     * Drops cached `courses_<semester>.json` files written by builds that
     * filed 選課清單 enrolments under the month heuristic instead of the term
     * the 選課 system was actually serving.
     *
     * NTUST opened 115-1 on 2026-08-20, weeks before the heuristic would have
     * rolled off 114-2. Any install that synced in that window has a
     * `courses_1142.json` holding a mix of both terms, and nothing rewrites it
     * on upgrade — the class table only refetches the semester it is showing,
     * and a non-empty cache renders as-is. See
     * [org.ntust.app.tigerduck.network.SemesterCatalog].
     *
     * Only the evictable `cacheDir` copies are dropped. Manual courses live in
     * `filesDir/manual_courses_<semester>.json`, are per-semester already, and
     * have no remote source to rebuild from — deleting those would destroy
     * courses the user typed in by hand.
     */
    private fun migrate2to3() {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
        deleteCourseCaches(cacheDir)
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

        /**
         * Unconditionally removes every remote course cache in [dir]. Unlike
         * [sweepCourseFiles] this does not inspect content — the wrong-semester
         * payload is indistinguishable from a correct one at the file level,
         * since both carry well-formed `"courseNo":` keys. The next sync
         * rebuilds each semester from its own sources.
         */
        internal fun deleteCourseCaches(dir: File) {
            if (!dir.isDirectory) return
            dir.listFiles()
                ?.filter {
                    it.isFile && (
                        it.name == LEGACY_COURSES_FILENAME ||
                            (it.name.startsWith(COURSES_PREFIX) && it.name.endsWith(".json"))
                        )
                }
                ?.forEach { file ->
                    runCatching {
                        if (file.delete()) {
                            Log.i(TAG, "Cleared wrong-semester course cache: ${file.name}")
                        }
                    }.onFailure { Log.w(TAG, "Failed to delete ${file.name}", it) }
                }
        }

        // Mirrors DataCache. Kept in sync deliberately — DataMigration must
        // run before any DataCache access, so we don't import the cache
        // class here to avoid pulling DI into the migration boot path.
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
        const val CURRENT_SCHEMA = 3

        /**
         * Lowest schema this build can migrate forward from. Anything below
         * this triggers [Outcome.NeedsUserReset]. Fresh installs come in at
         * 0 (the default), so keep this at 0 unless you intentionally want
         * to force all old installs to reset.
         */
        const val MIN_SUPPORTED_SCHEMA = 0
    }
}
