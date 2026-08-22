// The rules Home applies when a network fetch lands on top of cached data.
//
// The parts the class table needs too — roster ordering, the Moodle
// semester filter, the submission safety net — live in
// data.CourseRosterMerge. What is left here is Home-specific.
//
// Every function here answers the same question in a different shape: what
// does the user keep? A refresh must not silently drop a manually-added
// course, must not resurrect one the user deleted, must not throw away a
// colour they just picked, and must not un-submit an assignment because one
// Moodle call flaked. Each of those has been a bug, and each is one line of
// this file.
//
// Pure on purpose — extracted from HomeViewModel.fetchData and
// fetchCoursesAndAssignments, which are 200 lines of coroutine orchestration
// that no test can reach.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.shared.Course
import java.util.Calendar

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

    /**
     * `java.util.Calendar`'s Sunday-first weekday to the Monday-first index
     * course schedules are keyed by (Mon=1 … Sun=7).
     */
    fun weekdayIndex(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }
}
