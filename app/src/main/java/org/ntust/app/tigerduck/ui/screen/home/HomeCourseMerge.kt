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
// ClassTableCourseMerge, which additionally restores the manual flag and the
// per-locale rename; the two are deliberately not shared.
//
// Pure on purpose — extracted from HomeViewModel.fetchData, which is 200
// lines of coroutine orchestration that no test can reach.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.shared.Course

object HomeCourseMerge {

    /**
     * Fold a freshly fetched course list into what is already on disk.
     *
     * Three things survive the remote list:
     *
     * - **Colours.** Read from [cached] rather than from the fetch, because a
     *   colour the user picked seconds ago exists only locally — the remote
     *   rows carry whatever the server last saw, or nothing.
     * - **Manual courses.** Anything `isManual` that the remote does not
     *   mention is kept. The remote only knows enrolments; a course the user
     *   typed in is invisible to it and would otherwise vanish on refresh.
     * - **Deletions.** [deletedNos] is applied last, to the merged list, so a
     *   course the user deleted stays deleted even when the roster still
     *   returns it.
     */
    fun mergeRemote(
        remote: List<Course>,
        cached: List<Course>,
        deletedNos: Set<String>,
    ): List<Course> {
        val latestColors = cached.associate { it.courseNo to it.customColorHex }
        val fetched = remote.map { it.copy(customColorHex = latestColors[it.courseNo]) }
        val fetchedNos = fetched.mapTo(mutableSetOf()) { it.courseNo }
        val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
        return (fetched + manualLeftovers).filter { it.courseNo !in deletedNos }
    }
}
