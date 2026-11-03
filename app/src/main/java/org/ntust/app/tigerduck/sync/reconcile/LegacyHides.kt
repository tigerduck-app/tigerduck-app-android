package org.ntust.app.tigerduck.sync.reconcile

import android.util.Log
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * The course hides, each legacy bare one pinned to the terms of [semesters] whose cached courses
 * carry its number, and written so under the hides' lock ([DataCache.pinLegacyCourseHides],
 * [CourseTombstoneKeys.migrateLegacyEntries]). A bare hide, from a build before hides had terms,
 * hides its number in every term, so the per-term reconcile would keep flip-flopping it: one term
 * un-hides on the server's say-so and clears it for all the others. One no such term's courses
 * carry stays bare, still hidden everywhere, rather than guessed at.
 *
 * The one rule for both steps that read the hides by their terms: the course list's
 * ([RosterReconciler]), and before it in the same pass a first course sync on `/v4`
 * ([FirstSyncReconciler]), which queues each hide as its term's delete. Pinned by the course list
 * alone, a hide nothing queued is one the backend does not hold, which `/v4`'s membership takes
 * away (`CourseMembership`), and the hidden course came back.
 */
internal suspend fun DataCache.pinnedLegacyHides(semesters: Collection<String>, signIn: SignIn): Set<String> {
    val stored = loadDeletedCourseNos()
    if (stored.none { ':' !in it }) return stored
    val rosters = semesters.associateWith { semester -> loadCourses(semester).map { it.courseNo }.toSet() }
    // Under the hides lock: a bare one lifted while the rosters were read stays lifted.
    val migrated = pinLegacyCourseHides(stored, rosters, signIn)
    val before = stored.count { ':' !in it }
    val bare = migrated.count { ':' !in it }
    if (bare < before) Log.i(TAG, "[Sync] pinned ${before - bare} legacy tombstones to their terms")
    return migrated
}

private const val TAG = "HomeViewModel"
