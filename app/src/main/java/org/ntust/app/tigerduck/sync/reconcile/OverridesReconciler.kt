package org.ntust.app.tigerduck.sync.reconcile

import android.util.Log
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.BackgroundCourseOverrides
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.sendMovedColors
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject

/**
 * The course colors and custom names. They always run, the first sync of an account included, but
 * not over a re-enable check still waiting for the user's answer ([SyncPass.policy]). Returns
 * whether Home's course list was loaded for them, which is what lets the pass count them as taken.
 * In the background, over the current term's cached courses ([BackgroundCourseOverrides]), as the
 * worker has no course list on screen.
 */
class OverridesReconciler @Inject constructor(
    private val dataCache: DataCache,
    private val widgetUpdater: WidgetUpdater,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val syncPolicies: SyncPolicies,
    private val prefs: AppPreferences,
) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean {
        if (pass.mode == SyncMode.BACKGROUND) return reconcileCachedTerm(pass)
        val result = pass.result
        val state = pass.home
        val syncColors = pass.policy.applies(SyncCategory.COURSE_COLORS)
        val syncNames = pass.policy.applies(SyncCategory.COURSE_NAMES)
        if (result.courseOverrides.isEmpty() || !(syncColors || syncNames)) return state.allCourses.value.isNotEmpty()
        // Not over a colour or name this phone has waiting to go up,
        // or sent since the fetch: the snapshot's is the older.
        return applyCourseOverrides(
            state, result.courseOverrides, pass.signIn, { pendingCourseOverrides.held(result.fetchedAtMs) }, syncColors, syncNames,
        )
    }

    /**
     * The background worker's pass: the snapshot's colors and names over the current term's cached
     * courses ([BackgroundCourseOverrides.apply]), not over a color or name this phone has waiting
     * to go up or sent since the fetch; then the widgets, and the colors moved off a duplicate go
     * up. Returns whether the pass applied the colors or names at all.
     */
    private suspend fun reconcileCachedTerm(pass: SyncPass): Boolean {
        val result = pass.result
        val signIn = pass.signIn
        val syncColors = pass.policy.applies(SyncCategory.COURSE_COLORS)
        val syncNames = pass.policy.applies(SyncCategory.COURSE_NAMES)
        if (!(syncColors || syncNames) || result.courseOverrides.isEmpty()) return false
        val applied = BackgroundCourseOverrides.apply(
            dataCache, dataCache.currentSemesterCode(), result.courseOverrides, { pendingCourseOverrides.held(result.fetchedAtMs) },
            syncColors, syncNames, AppLanguageManager.resolvedCourseApiLanguage(prefs.appLanguage), signIn,
        ) ?: return true
        widgetUpdater.requestUpdate()
        sendMovedColors(applied.courses, applied.moved) { moodleId, hex ->
            pendingCourseOverrides.send(moodleId, colorHex = hex, signIn = signIn)
        }
        return true
    }

    /**
     * The conflict dialog's "keep the server's" for the colors and names of [result], the snapshot
     * that was contested, under the switches and the hold as they stand now.
     */
    suspend fun keepServer(state: HomeSyncState, result: BackendSyncResult, signIn: SignIn) {
        if (result.courseOverrides.isEmpty()) return
        val policy = syncPolicies.current
        applyCourseOverrides(
            state, result.courseOverrides, signIn, { pendingCourseOverrides.held(result.fetchedAtMs) },
            policy.applies(SyncCategory.COURSE_COLORS), policy.applies(SyncCategory.COURSE_NAMES),
        )
    }

    /**
     * Returns false when it applied nothing, the course list not having
     * loaded. [held] reads what the snapshot must not overwrite; see
     * [PendingCourseOverrides.held]. The colours take it at the start, the
     * names again under their lock (see [CourseSyncReconciler.applyNameOverrides]).
     */
    private suspend fun applyCourseOverrides(
        state: HomeSyncState,
        overrides: List<CourseOverrideResult>,
        signIn: SignIn,
        held: suspend () -> Set<String>,
        syncColors: Boolean,
        syncNames: Boolean,
    ): Boolean {
        // Read before anything is applied, as the colours always took it.
        val heldColors = held()
        // The early return on an empty course list also skips the custom-name
        // merge below. That coupling predates this refactor and is preserved
        // deliberately: changing it would make names land on a device whose
        // course list has not loaded yet, which is a behaviour change and not
        // this commit's business.
        val courses = state.allCourses.value.ifEmpty { return false }

        CourseSyncReconciler.applyNameOverrides(dataCache, overrides, syncNames, signIn, held)

        CourseSyncReconciler.applyColorOverrides(
            courses = courses,
            overrides = overrides,
            syncColors = syncColors,
            held = heldColors,
        )?.let { outcome ->
            val updated = outcome.courses
            Log.i(TAG, "[sync-color] applying ${updated.count { c -> c.customColorHex != null }} custom colors")
            if (signIn.ifCurrent { state.allCourses.value = updated } != null) TigerDuckTheme.buildCourseColorMap(updated)
            dataCache.saveCourses(updated, signIn)
            widgetUpdater.requestUpdate()
            sendMovedColors(updated, outcome.moved) { moodleId, hex ->
                pendingCourseOverrides.send(moodleId, colorHex = hex, signIn = signIn)
            }
        }
        return true
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
