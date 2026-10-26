// How Home folds a freshly fetched course list onto the cached one.
//
// One question, asked three ways: what does the user keep? A refresh must
// not silently drop a course they added by hand, must not resurrect one they
// deleted, and must not throw away a colour they just picked. Each of those
// has been a bug, and each is one line below.
//
// The rules the class table needs too — roster ordering, the Moodle semester
// filter, the submission safety net, the weekday mapping — live in
// data.CourseRosterMerge. The class table's own version of this merge is
// ClassTableCourseMerge; the two are deliberately not shared, but both must
// restore the manual flag and look the per-locale rename up the same way
// because they write the same cache file.
//
// Pure on purpose — extracted from HomeViewModel.fetchData, which is 200
// lines of coroutine orchestration that no test can reach.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.CourseNameOverrides
import org.ntust.app.tigerduck.ui.screen.classtable.CustomNameMap

object HomeCourseMerge {

    /**
     * Fold a freshly fetched course list into what is already on disk.
     *
     * What survives the remote list:
     *
     * - **Colours.** Read from [cached] rather than from the fetch, because a
     *   colour the user picked seconds ago exists only locally — the remote
     *   rows carry whatever the server last saw, or nothing.
     * - **Manual courses.** Anything `isManual` that the remote does not
     *   mention is kept. The remote only knows enrolments; a course the user
     *   typed in is invisible to it and would otherwise vanish on refresh.
     * - **Renames.** Looked up in the stored [names] for [locale], as the
     *   class table does, so one cleared there goes here too. This file is
     *   what the widgets, the Live Update and the watch read, and none of
     *   them looks a rename up again.
     * - **The manual flag itself.** A hand-added course the school *also*
     *   lists comes back through [remote] with `isManual` at its default of
     *   false, so the flag has to be restored from [cached] the same way the
     *   colour is. `CourseSyncReconciler.reconcileDeletions` keys its
     *   "never tombstone a manual course on server-absence" guard on this
     *   flag, and Home and the class table write the same cache file — so
     *   dropping it here would make a course's survival depend on which
     *   screen refreshed last.
     * - **Deletions.** [deletedNos] is applied last, to the merged list, so a
     *   course the user deleted stays deleted even when the roster still
     *   returns it.
     */
    fun mergeRemote(
        remote: List<Course>,
        cached: List<Course>,
        names: CustomNameMap,
        locale: String,
        deletedNos: Set<String>,
    ): List<Course> {
        val cachedByNo = cached.associateBy { it.courseNo }
        val fetched = remote.map { course ->
            val prior = cachedByNo[course.courseNo]
            course.copy(
                customColorHex = prior?.customColorHex,
                customCourseName = CourseNameOverrides.overrideFor(names, course.courseNo, locale),
                isManual = prior?.isManual == true,
            )
        }
        val fetchedNos = fetched.mapTo(mutableSetOf()) { it.courseNo }
        val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
        return CourseNameOverrides.resolve(fetched + manualLeftovers, names, locale)
            .filter { it.courseNo !in deletedNos }
    }
}
