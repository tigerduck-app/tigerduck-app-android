// Folding a freshly fetched timetable back onto the one already on disk.
//
// The fetch knows the school's view: schedule, room, credits, instructor. It
// knows nothing about what the user did to that view — the colour they
// picked, the course they typed in by hand, the name they rewrote, the course
// they deleted. Each of those has to be carried across explicitly, and each
// one that is missed is a user-visible regression on the next refresh.
//
// Home has its own version of this (HomeCourseMerge.mergeRemote). They are
// deliberately not shared: the class table is per-semester and additionally
// restores the manual flag and the per-locale rename, neither of which Home's
// current-term-only path deals with.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.shared.Course

object ClassTableCourseMerge {

    /**
     * Merge [fetched] over [cached] for one semester.
     *
     * Carried across from the cached copy of the same course: the colour pick
     * and the `isManual` flag. A course the user added by hand that the
     * school now also lists must not lose its flag, or deleting it later
     * would take the wrong path.
     *
     * Carried across from [names]: the rename, resolved for [locale]. Done
     * here rather than by a later pass so a language switch mid-refresh
     * cannot stamp the previous locale's name.
     *
     * Kept from [cached]: manual courses the fetch does not mention, since
     * the school's roster has never heard of them.
     *
     * Dropped last: anything in [deletedNos], applied to the merged list so a
     * deleted course cannot re-enter through either half.
     */
    fun mergeFetched(
        fetched: List<Course>,
        cached: List<Course>,
        names: CustomNameMap,
        locale: String,
        deletedNos: Set<String>,
    ): List<Course> {
        val cachedByNo = cached.associateBy { it.courseNo }
        val restored = fetched.map { course ->
            val prior = cachedByNo[course.courseNo]
            course.copy(
                customColorHex = prior?.customColorHex,
                isManual = prior?.isManual == true,
                customCourseName = CourseNameOverrides.overrideFor(names, course.courseNo, locale),
            )
        }
        val fetchedNos = restored.mapTo(mutableSetOf()) { it.courseNo }
        val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
        return CourseNameOverrides.resolve(restored + manualLeftovers, names, locale)
            .filter { it.courseNo !in deletedNos }
    }
}
