package org.ntust.app.tigerduck.sync.reconcile

import android.util.Log
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.BackgroundCourseOverrides
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.home.StampedMerges
import org.ntust.app.tigerduck.ui.screen.home.sendMovedColors
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject

/**
 * The course colors and custom names. They always run, the first sync of an account included, but
 * not over a re-enable check still waiting for the user's answer ([SyncPass.policy]). Returns
 * whether Home's course list was loaded for them, which is what lets the pass count them as taken.
 * In the background, over the current term's cached courses ([BackgroundCourseOverrides]), as the
 * worker has no course list on screen. Each field is the snapshot's, its absence included, but
 * those this phone keeps (LWW D12, [SyncPass.holds]).
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
        val state = pass.home
        val syncColors = pass.policy.applies(SyncCategory.COURSE_COLORS)
        val syncNames = pass.policy.applies(SyncCategory.COURSE_NAMES)
        // An empty snapshot is news too: a color or name it lacks goes (LWW D12).
        if (!(syncColors || syncNames)) return state.allCourses.value.isNotEmpty()
        return applyStamped(state, pass.result, pass.holds(), pass.signIn, syncColors, syncNames)
    }

    /**
     * The background worker's pass: the snapshot's colors and names over the current term's cached
     * courses ([BackgroundCourseOverrides.apply]), then the widgets, and the colors moved off a
     * duplicate go up. Returns whether the pass applied the colors or names at all.
     *
     * The switches and the re-enable hold are read here, once the steps before have run, as the
     * worker always read them, not from the pass's reading: a switch turned off while the ignores
     * step waits on its requests keeps the snapshot's colors and names off the timetable and the
     * widgets. A category never synced here is Home's first sync's, which queues this phone's
     * values first (LWW D13, C2).
     */
    private suspend fun reconcileCachedTerm(pass: SyncPass): Boolean {
        val result = pass.result
        val signIn = pass.signIn
        val policy = syncPolicies.current
        val holds = pass.holds()
        val syncColors = policy.applies(SyncCategory.COURSE_COLORS) && SyncCategory.COURSE_COLORS in pass.syncedBefore
        val syncNames = policy.applies(SyncCategory.COURSE_NAMES) && SyncCategory.COURSE_NAMES in pass.syncedBefore
        if (!(syncColors || syncNames)) return false
        val term = dataCache.currentSemesterCode()
        val applied = BackgroundCourseOverrides.apply(
            dataCache, term, syncColors, syncNames, AppLanguageManager.resolvedCourseApiLanguage(prefs.appLanguage), signIn,
            stamped(result, holds, term),
        ) ?: return true
        widgetUpdater.requestUpdate()
        sendMovedColors(applied.courses, applied.moved) { moodleId, hex ->
            pendingCourseOverrides.send(moodleId, colorHex = hex, signIn = signIn)
        }
        return true
    }

    // The pull's merges over [term]'s courses, the override channel's waiting changes and every term's courses read at each.
    private fun stamped(result: BackendSyncResult, holds: PullHolds, term: String) = StampedMerges(
        result.courseOverrides, term, holds, pendingCourseOverrides::heldFields, { dataCache.loadEveryTermCourses() },
        { dataCache.currentSemesterCode() to pendingCourseOverrides.catalogue() },
    )

    /**
     * Each field the snapshot's, its absence included, but those this phone keeps (LWW D12): over
     * Home's term's courses as they stand, in one step under their lock with the names'
     * ([BackgroundCourseOverrides.apply], the background's own), so the colors are decided from
     * the list that step commits, and a pick saved meanwhile is not saved over; and the names are
     * stamped onto the courses Home and the widgets show them from. Then Home shows the list
     * written. Returns false when it applied nothing, the course list not having loaded.
     */
    private suspend fun applyStamped(
        state: HomeSyncState,
        result: BackendSyncResult,
        holds: PullHolds,
        signIn: SignIn,
        syncColors: Boolean,
        syncNames: Boolean,
    ): Boolean {
        if (state.allCourses.value.isEmpty()) return false
        val term = dataCache.currentSemesterCode()
        val applied = BackgroundCourseOverrides.apply(
            dataCache, term, syncColors, syncNames,
            AppLanguageManager.resolvedCourseApiLanguage(prefs.appLanguage), signIn, stamped(result, holds, term),
        ) ?: return true
        showColors(state, applied, signIn)
        return true
    }

    // The colors [outcome] applied and saved, on Home, and the colors it moved off a duplicate sent.
    private suspend fun showColors(state: HomeSyncState, outcome: CourseSyncReconciler.ColorOutcome, signIn: SignIn) {
        val updated = outcome.courses
        Log.i(TAG, "[sync-color] applying ${updated.count { c -> c.customColorHex != null }} custom colors")
        if (signIn.ifCurrent { state.allCourses.value = updated } != null) TigerDuckTheme.buildCourseColorMap(updated)
        widgetUpdater.requestUpdate()
        sendMovedColors(updated, outcome.moved) { moodleId, hex ->
            pendingCourseOverrides.send(moodleId, colorHex = hex, signIn = signIn)
        }
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
