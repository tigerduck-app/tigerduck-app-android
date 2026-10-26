package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import org.ntust.app.tigerduck.data.preferences.AppPreferences

private const val TAG = "SyncReenable"

/** Every category the re-enable check compares. */
internal val REENABLE_CATEGORIES = setOf("courses", "course_colors", "course_names", "assignments")

/**
 * The re-enable check's categories whose sync switch is on. `HomeBackendSync.pull`
 * reads them once for its course steps and, when it succeeds, adds them to
 * [AppPreferences.syncedCategories].
 */
internal fun categoriesSwitchedOn(prefs: AppPreferences): Set<String> = buildSet {
    if (prefs.syncCourses) add("courses")
    if (prefs.syncCourseColors) add("course_colors")
    if (prefs.syncCourseNames) add("course_names")
    if (prefs.syncAssignments) add("assignments")
}

/**
 * What a full sync that read [switchedOn] records in
 * [AppPreferences.syncedCategories] once it has run: the categories whose
 * server data it [took] (see [categoriesTaken]), and assignments only while
 * their switch is still on, since one turned off meanwhile stopped the sync
 * from taking the marks (see `AssignmentMarkStep`).
 */
internal fun categoriesSynced(
    switchedOn: Set<String>,
    took: Set<String>,
    prefs: AppPreferences,
): Set<String> {
    val synced = switchedOn intersect took
    return if (prefs.syncAssignments) synced else synced - "assignments"
}

/**
 * The categories whose server data a full sync took, from its own decisions:
 * the marks when its mark step took them or sent them up as the first sync
 * ([marksTaken]); the course list when it reconciled it ([courseListSynced]);
 * colours and names when it applied them ([syncColors], [syncNames]), which
 * it does only over a loaded course list ([coursesLoaded]), unless the
 * server had none to take ([hasOverrides] false). A category it skipped, as
 * one held for its re-enable check, is not taken: recorded as synced, a
 * later check would compare data this phone never took, and Use Local would
 * send it over the server's (tigerduck-app/tigerduck-app#424).
 */
internal fun categoriesTaken(
    marksTaken: Boolean,
    courseListSynced: Boolean,
    syncColors: Boolean,
    syncNames: Boolean,
    hasOverrides: Boolean,
    coursesLoaded: Boolean,
): Set<String> = buildSet {
    if (marksTaken) add("assignments")
    if (courseListSynced) add("courses")
    val overridesApplied = !hasOverrides || coursesLoaded
    if (syncColors && overridesApplied) add("course_colors")
    if (syncNames && overridesApplied) add("course_names")
}

/**
 * Marks [category], switched back on, for the re-enable check, which compares
 * this phone's copy of the account's data with the server's. Before a full
 * sync has taken the category's data for the account here, as after a sign-in,
 * there is no copy: Use Local would send the emptied caches over the server's,
 * so the category is left to its first sync, which takes the server's data.
 * Signed out, the check has no session, and the mark would wait for whoever
 * signs in next.
 */
internal fun markForReenableCheck(prefs: AppPreferences, category: String, signedIn: Boolean) {
    val synced = category in prefs.syncedCategories
    if (!signedIn || !synced) {
        Log.i(TAG, "[reenable] not marked: category=$category signedIn=$signedIn synced=$synced")
        return
    }
    prefs.pendingConflictCategories = prefs.pendingConflictCategories + category
    Log.i(TAG, "[reenable] marked category=$category pending=${prefs.pendingConflictCategories}")
}

/**
 * The categories of an answered re-enable dialog still held once the user
 * taps: the ones whose answer sends or takes data after the tap, so a full
 * sync in between cannot write the server's over what Use Local is about to
 * send, or take it in Use Server's stead. "courses" is not among them: Use
 * Local latches its terms instead (`handOverCourseHold`). Let go once the
 * answer has been applied.
 */
internal fun heldUntilAnswered(categories: Collection<String>): Set<String> =
    categories.filterTo(mutableSetOf()) { it in HELD_UNTIL_ANSWERED }

private val HELD_UNTIL_ANSWERED = setOf("assignments", "course_colors", "course_names")

/**
 * The categories [pending] when the dialog over [answered] is answered that
 * the dialog does not cover: switched back on while its check ran. The check
 * that covers them started before the answer, which drops its result
 * (`ReenableResolutions.isCurrent`), so they stay pending and are checked
 * again once the answer has been applied. As iOS
 * (tigerduck-app/tigerduck-app#602).
 */
internal fun unanswered(pending: Set<String>, answered: Collection<String>): Set<String> =
    pending - answered.toSet()
