package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the re-enable check is owed a run that no Settings screen asked
 * for: no check has given a verdict in this process, or the last one failed
 * or had its result dropped. Until a verdict, the categories it holds wait,
 * the course ignores among them, which the server goes on reminding about.
 */
internal class OwedReenableCheck {
    @Volatile
    private var owed = true
    private val running = AtomicInteger()

    /** Whether to run it now: owed, something [pending], sync on, and no check running. */
    fun due(pending: Set<String>, syncOn: Boolean): Boolean =
        syncOn && pending.isNotEmpty() && owed && running.get() == 0

    fun started() {
        running.incrementAndGet()
    }

    /** A check ended, with a verdict (a difference found, or none) or without one. */
    fun ended(verdict: Boolean) {
        owed = !verdict
        running.decrementAndGet()
    }
}

/**
 * The re-enable check: after a sync category is switched back on, compare
 * this phone's copy of its data with the server's, and either let the
 * category go (no difference) or ask Use Server or Use Local
 * ([SettingsViewModel.ReenableConflict]). Every Settings screen runs it as it
 * opens; a full sync or a revision poll that reaches the server runs it again
 * when it is owed ([runIfOwed]), so a check that failed offline does not hold
 * the categories until the next Settings visit. A difference found then is
 * asked about at that visit. As iOS (tigerduck-app/tigerduck-app#368).
 */
@Singleton
class ReenableCheck @Inject constructor(
    private val prefs: AppPreferences,
    private val syncApiClient: SyncApiClient,
    private val dataCache: DataCache,
    private val semesterCatalog: SemesterCatalog,
    private val courseIgnoreSync: CourseIgnoreSync,
    private val authService: AuthService,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:ApplicationContext private val context: Context,
) {
    /** The answers being applied, which every Settings screen's view model shares. */
    internal val resolutions = ReenableResolutions()

    private val owed = OwedReenableCheck()

    /** After a full sync or a revision poll reached the server: runs the check if it is owed. */
    fun runIfOwed() {
        if (!owed.due(prefs.pendingConflictCategories, prefs.cloudSyncEnabled)) return
        Log.i(TAG, "[reenable] running the owed check")
        // Not while an answer is applied: that one settles the hold itself,
        // and a Settings screen's check waiting for it must stay the one
        // that runs after it.
        check(waitForAnswers = false) { /* asked at the next Settings visit, whose check finds it again */ }
    }

    /**
     * Runs the check over the categories pending now, handing a difference to
     * [onConflict]. On the application scope: a check cut short by leaving
     * Settings would keep "assignments" pending, and the marks held, until
     * TigerSync is opened again. On Main, like the view models' work.
     */
    fun check(waitForAnswers: Boolean = true, onConflict: (SettingsViewModel.ReenableConflict) -> Unit) {
        val pending = prefs.pendingConflictCategories
        if (pending.isEmpty()) {
            Log.d(TAG, "[reenable] checkPendingConflicts skip: pending is empty")
            return
        }
        val token = resolutions.checkStarting(waits = waitForAnswers) { check(onConflict = onConflict) } ?: return
        Log.i(TAG, "[reenable] checkPendingConflicts start: pending=$pending")
        val account = authService.storedStudentId
        val generation = dataCache.userDataGeneration
        owed.started()
        appScope.launch(Dispatchers.Main) {
            var verdict = false
            try {
                val result = syncApiClient.fetchFullSync()
                val diffs = differences(pending, result)
                Log.i(TAG, "[reenable] result: ${diffs.size} diffs → ${if (diffs.isEmpty()) "no conflict" else "SHOW POPUP"}")
                // An answer given since this check began changed what it
                // compared, and settles the pending set itself. After a
                // sign-out the result is the departed account's.
                val stillApplies = resolutions.isCurrent(token) &&
                    authService.isStillSignedInAs(account) &&
                    dataCache.userDataGeneration == generation
                if (!stillApplies) return@launch
                verdict = true
                if (diffs.isNotEmpty()) {
                    onConflict(
                        SettingsViewModel.ReenableConflict(
                            categories = pending.toList(),
                            description = diffs.joinToString("\n"),
                            checked = result,
                        )
                    )
                } else {
                    // Only what this check compared: one started later may
                    // still hold "assignments" for its own dialog.
                    prefs.pendingConflictCategories = prefs.pendingConflictCategories - pending
                    // The course ignores it held go out now, not at the next sync.
                    if ("assignments" in pending) courseIgnoreSync.retryPending()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[reenable] checkPendingConflicts FAILED — pending kept for retry: ${e.logSummary()}")
            } finally {
                owed.ended(verdict)
            }
        }
    }

    /** The differences [result] shows in the [pending] categories, as the dialog words them. */
    private suspend fun differences(pending: Set<String>, result: BackendSyncResult): List<String> {
        val diffs = mutableListOf<String>()

        if ("courses" in pending) {
            // Every term Use Local acts on: the server's and the
            // catalogue's — see courseListDifference.
            val terms = (result.serverCourses.map { it.semester } + semesterCatalog.availableSemesters())
                .filterTo(sortedSetOf()) { it.isNotBlank() }
            val cached = terms.associateWith { term -> dataCache.loadCourses(term).map { it.courseNo } }
            val diff = courseListDifference(result.serverCourses, cached, dataCache.loadDeletedCourseNos())
            val localOnly = diff.localOnly
            val serverOnly = diff.serverOnly
            Log.i(TAG, "[reenable] courses: terms=${terms.size} localOnly=$localOnly serverOnly=$serverOnly")
            if (diff.differs) {
                diffs.add(when {
                    localOnly > 0 && serverOnly > 0 -> context.getString(R.string.sync_conflict_reenable_courses, localOnly.toString(), serverOnly.toString())
                    localOnly > 0 -> context.getString(R.string.sync_conflict_reenable_courses_local_only, localOnly.toString())
                    else -> context.getString(R.string.sync_conflict_reenable_courses_server_only, serverOnly.toString())
                })
            } else {
                Log.i(TAG, "[reenable] courses MATCH — no conflict")
            }
        }

        if ("course_colors" in pending) {
            val courses = dataCache.loadCourses()
            val localColors = courses.associate { it.courseNo to it.customColorHex }
            var colorMismatches = 0
            for (override in result.courseOverrides) {
                val courseNo = override.courseNo ?: continue
                val serverHex = override.colorHex ?: continue
                val localHex = localColors[courseNo]
                if (localHex != serverHex) {
                    colorMismatches++
                    Log.d(TAG, "[reenable] color mismatch: $courseNo local=$localHex server=$serverHex")
                }
            }
            Log.i(TAG, "[reenable] colors: ${if (colorMismatches == 0) "MATCH" else "DIFFER ($colorMismatches)"}")
            if (colorMismatches > 0) {
                diffs.add(context.getString(R.string.sync_conflict_reenable_colors_differ))
            }
        }

        if ("course_names" in pending) {
            val nameMismatches = courseNameMismatches(dataCache.loadCourseCustomNames(), result.courseOverrides)
            Log.i(TAG, "[reenable] names: ${if (nameMismatches == 0) "MATCH" else "DIFFER ($nameMismatches)"}")
            if (nameMismatches > 0) {
                diffs.add(context.getString(R.string.sync_conflict_reenable_names_differ))
            }
        }

        if ("assignments" in pending) {
            val localIgnored = dataCache.loadIgnoredAssignments()
            val localCompleted = dataCache.loadMarkedCompletedAssignments()
            val ignoredMatch = localIgnored == result.ignoredIds
            val completedMatch = localCompleted == result.completedIds
            // Course ignores follow course sync too, so with it off there is nothing to compare.
            val coursesMatch = !prefs.syncCourses || courseIgnoreSync.agreesWith(result)
            Log.i(TAG, "[reenable] assignments: localIgnored=${localIgnored.size} serverIgnored=${result.ignoredIds.size} match=$ignoredMatch | localCompleted=${localCompleted.size} serverCompleted=${result.completedIds.size} match=$completedMatch | ignoredCourses match=$coursesMatch")
            if (!ignoredMatch || !completedMatch || !coursesMatch) {
                diffs.add(context.getString(R.string.sync_conflict_reenable_assignments_differ))
            }
        }
        return diffs
    }

    private companion object {
        const val TAG = "SyncReenable"
    }
}

/**
 * How many courses' names differ between [localNames] and the server's [overrides].
 * A name the server holds as "" was cleared, so it counts as none.
 */
internal fun courseNameMismatches(
    localNames: Map<String, Map<String, String>>,
    overrides: List<CourseOverrideResult>,
): Int {
    var mismatches = 0
    val serverNosWithNames = mutableSetOf<String>()
    for (override in overrides) {
        val courseNo = override.courseNo ?: continue
        val serverNames = override.customNames.filterValues { it.isNotEmpty() }
        if (serverNames.isEmpty()) continue
        serverNosWithNames.add(courseNo)
        if ((localNames[courseNo] ?: emptyMap()) != serverNames) {
            mismatches++
            Log.d("SyncReenable", "[reenable] name mismatch: $courseNo local=${localNames[courseNo]} server=$serverNames")
        }
    }
    for ((courseNo, locales) in localNames) {
        if (locales.isNotEmpty() && courseNo !in serverNosWithNames) {
            mismatches++
            Log.d("SyncReenable", "[reenable] name mismatch: $courseNo local=$locales server=default")
        }
    }
    return mismatches
}
