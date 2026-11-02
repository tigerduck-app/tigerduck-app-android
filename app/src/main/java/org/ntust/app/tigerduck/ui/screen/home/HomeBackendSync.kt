// Cross-device sync for everything Home owns: assignment overrides, course
// colours and custom names, and the course list itself.
//
// The full sync itself is the Synchronizer's (sync/Synchronizer.kt), each
// category's step a reconciler in a pinned order (sync/reconcile/). What is
// left here is Home's side of it: the state the sync updates, the 401 relogin,
// and the answer to the conflict dialog.

package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.isSessionRefused
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.Synchronizer
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject

/**
 * The slice of HomeViewModel that a sync reads and writes.
 *
 * Passed in rather than injected because these are the ViewModel's own
 * flows — the sync updates the live UI state directly, the same way the
 * inline version did. Holding them here instead of copying values in and out
 * keeps the "user taps mid-sync" behaviour identical: the reconciler reads
 * [pendingOverrides] at the moment it runs, not a snapshot from before the
 * network call.
 */
class HomeSyncState(
    val ignoredAssignmentIds: MutableStateFlow<Set<String>>,
    val markedCompletedIds: MutableStateFlow<Set<String>>,
    /** The ignored courses; see [org.ntust.app.tigerduck.data.CourseIgnores]. */
    val ignoredCourseKeys: MutableStateFlow<Set<String>>,
    val allAssignments: MutableStateFlow<List<Assignment>>,
    val allCourses: MutableStateFlow<List<Course>>,
    val conflicts: MutableStateFlow<List<AssignmentSyncConflict>>,
    /**
     * Ids with an override PATCH in flight. Mutated by HomeViewModel's toggle
     * handlers on the main thread and read here; see the reconciler for why
     * these are exempt from server state.
     */
    val pendingOverrides: MutableSet<String>,
) {
    /**
     * Held from the sync that produced a conflict until the user answers the
     * dialog, because "keep the server's version" has to apply the result
     * that was contested, not whatever the server says by the time they tap.
     */
    var pendingResult: BackendSyncResult? = null

    /** Last revision the backend reported for this sign-in; the poller compares against it. */
    val lastKnownRevision = org.ntust.app.tigerduck.shared.account.SignInBound(0L)
}

class HomeBackendSync @Inject constructor(
    private val synchronizer: Synchronizer,
    private val authService: AuthService,
    private val pushApiClient: PushApiClient,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val marks: MarksReconciler,
    private val overrides: OverridesReconciler,
) {

    /**
     * Sends what waits to go up: course-ignore changes, marks, course adds and
     * deletes, course colours and names, and holiday choices. A full sync
     * starts with this; see [Synchronizer.retryUnsent].
     */
    suspend fun retryUnsent(signIn: SignIn) = synchronizer.retryUnsent(signIn)

    /**
     * What a full sync sends besides taking its snapshot, for a revision poll
     * that moves the watermark without one (`pollRevisionOnce`): what waits
     * ([retryUnsent]), and a notification-settings edit whose push never
     * landed, which [pull] re-sends once it lands. The feed read before this
     * proves a session and a network as a full sync does. As the iPhone's
     * `retryUnsentEdits`.
     */
    suspend fun sendWhatWaits(signIn: SignIn) {
        retryUnsent(signIn)
        notificationSettingsSync.pushIfUnconfirmed()
    }

    /**
     * Pull everything from the backend and reconcile it into [state]: the
     * [Synchronizer]'s full sync, in the foreground.
     *
     * A 401 gets one relogin, and the whole sync runs again, as the failed
     * one reports itself: [retried] guards that against looping, so a
     * relogin that still produces a 401 gives up rather than recursing. A
     * run past its sign-out signs nobody in. [signIn] is the one this sync
     * runs under, read once where it starts.
     */
    suspend fun pull(state: HomeSyncState, signIn: SignIn, retried: Boolean = false) {
        synchronizer.sync(SyncMode.FOREGROUND, signIn, state) { e ->
            if (!retried && e.isSessionRefused()) {
                // Delegates to AuthService so the relogin harvests a fresh
                // Moodle token — the backend rejects stale stored tokens with
                // 401 invalid_token.
                if (authService.attemptRelogin(signIn)) {
                    pull(state, signIn, retried = true)
                }
            }
        }
    }

    /**
     * Answer to the conflict dialog. Keeping local re-asserts it upstream.
     *
     * [result] and [conflicts] are passed in rather than read from [state]
     * because the caller clears both synchronously to dismiss the dialog —
     * by the time this runs they are already gone from the live state.
     */
    suspend fun applyResolution(
        state: HomeSyncState,
        result: BackendSyncResult,
        conflicts: List<AssignmentSyncConflict>,
        keepLocal: Boolean,
        signIn: SignIn,
    ) {
        if (keepLocal) {
            for (c in conflicts) {
                val mid = c.id.toIntOrNull() ?: continue
                runCatching { pushApiClient.patchAssignmentOverride(mid, c.localStatus, signIn) }
            }
        } else {
            marks.keepServer(state, result, signIn)
            overrides.keepServer(state, result, signIn)
        }
    }
}

/**
 * A term's reconcile committed to [dataCache], by Home's sync and the background one: the hides
 * it changed, from [read], the hides it was handed, to [hides], the marks of those
 * [tombstoneNos], the course numbers its snapshot's tombstones name, hold let go of, and the
 * term's courses, without those it pruned from [localCourses], its own load ([kept] says which
 * stay, null when it pruned none), without the dropped courses it lets go with no hide ([left]),
 * those still dropped as the commit finds the drop list and as [droppedOnServer], the snapshot's
 * rows a roster dropped by when, says, but those the term's latest course selection answer, read
 * here with when it arrived, listed since the drop (`SelectionAnswer.listsSince`), and with
 * the rows it [merged] down that the drop list, as the commit finds it too, does not name, or that
 * were added by hand ([handAdded]). Each is applied to the store as it stands, so a delete, a
 * fetch or a hide lifted while the sync ran stays. Returns whether it wrote the courses.
 *
 * The hides first: the courses keep out every merged row the store hides when they are written,
 * and a course another device added back has its row merged and its hide lifted by the same
 * reconcile, so written the other way round it stays missing until the next sync. A hide a
 * delete wrote since the read is no change of this reconcile's, and still keeps its row out.
 * The marks go with the hides, in one write: a delete made before it marks its hide, and one
 * made after finds the course hidden and marks nothing (`DataCache.hideDeletedCourse`). A course
 * selection answer that lifted a tombstoned hide meanwhile is undone, and every course the
 * commit hid goes from the term, wherever the term has it now.
 *
 * A course put back in the term since [courseSync], the sync's, began is neither hidden nor
 * pruned for the older read: added back by hand, or brought back where the server is silent
 * (`DataCache.commitSyncHides`). Its hide not written, its row stays, as one whose hide was lifted.
 *
 * A course the commit takes out of the term, hidden since here or on another device, or dropped,
 * takes its waiting colour and name in [overrides] with it, those set before the commit wrote the term:
 * a course put back meanwhile stays, and so does what waits for it. The two go together however
 * the sync is cancelled: a course gone with its changes left waiting would have them refused at
 * every sync, and given to it again if it came back.
 */
internal suspend fun commitReconciledTerm(
    dataCache: DataCache,
    courseSync: DataCache.CourseSync,
    semester: String,
    read: Set<String>,
    hides: Set<String>,
    tombstoneNos: Set<String>,
    localCourses: List<Course>,
    kept: List<Course>?,
    left: Set<String>,
    droppedOnServer: Map<String, Long?>,
    merged: List<Course>,
    handAdded: Set<String>,
    overrides: PendingCourseOverrides,
    signIn: SignIn,
): Boolean {
    val newlyHidden = dataCache.commitSyncHides(semester, read, hides, tombstoneNos, courseSync, signIn)
    if (kept == null && left.isEmpty() && merged.isEmpty() && newlyHidden.isEmpty()) return false
    withContext(NonCancellable) {
        val before = overrides.reserveVersion()
        var removed = emptyList<Course>()
        dataCache.updateCourses(semester, signIn) { current, hidden ->
            // Read with the term's courses: a fetch records its roster before it saves them, so
            // a rebuild that saved since the reconcile has its drops in this read.
            val droppedHere = if (left.isEmpty() && merged.isEmpty()) emptySet() else {
                dataCache.loadSelectionDroppedNos()[semester].orEmpty().toSet()
            }
            val answer = if (left.isEmpty()) null else dataCache.selectionAnswerIn(semester)
            CourseSyncReconciler.committedCourses(
                current, localCourses, kept ?: localCourses, merged, hidden, newlyHidden, left, droppedHere, droppedOnServer,
                handAdded, answer,
            ).also { committed ->
                val stays = committed.mapTo(mutableSetOf()) { it.courseNo }
                removed = current.filter { it.courseNo !in stays }
            }
        }
        overrides.forget(removed.mapNotNull { it.moodleIdNumber }, madeBefore = before, signIn = signIn)
    }
    return true
}

/**
 * Sends each course the colour pass moved ([moved], by course number) its new
 * colour through [patch], by the Moodle id the backend keys the course by, in
 * course-number order. A course without one is skipped, as a pick on the class
 * table skips it. Through [PendingCourseOverrides], which keeps a colour that
 * fails to land, and in place of an older colour of the course still waiting.
 */
internal suspend fun sendMovedColors(
    courses: List<Course>,
    moved: Map<String, String>,
    patch: suspend (moodleId: String, hex: String) -> Unit,
) {
    for ((courseNo, hex) in moved.toSortedMap()) {
        val moodleId = courses.find { it.courseNo == courseNo }?.moodleIdNumber ?: continue
        try {
            patch(moodleId, hex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HomeViewModel", "[sync-color] moved colour PATCH failed: ${e.logSummary()}")
        }
    }
}
