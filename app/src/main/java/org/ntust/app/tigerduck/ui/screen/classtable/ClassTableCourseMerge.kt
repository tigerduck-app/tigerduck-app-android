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
            // A course added here keeps the Moodle identity it was added with, none, as [refreshed]
            // keeps it: with the school's it would read as merged, which a past term's rebuild drops.
            val addedHere = prior != null && prior.isManual && prior.moodleIdNumber.isNullOrEmpty()
            course.copy(
                customColorHex = prior?.customColorHex,
                isManual = prior?.isManual == true,
                customCourseName = CourseNameOverrides.overrideFor(names, course.courseNo, locale),
                moodleIdNumber = if (addedHere) prior?.moodleIdNumber else course.moodleIdNumber,
                moodleNumericCourseId = if (addedHere) prior?.moodleNumericCourseId else course.moodleNumericCourseId,
            )
        }
        val fetchedNos = restored.mapTo(mutableSetOf()) { it.courseNo }
        val manualLeftovers = cached.filter { it.isManual && it.courseNo !in fetchedNos }
        return CourseNameOverrides.resolve(restored + manualLeftovers, names, locale)
            .filter { it.courseNo !in deletedNos }
    }

    // --- Looking a stored course up again (tigerduck-app-android#264) ---
    //
    // mergeFetched keeps a hand-added course, or one merged from another
    // device, exactly as stored: the snapshot taken when it was added. A room
    // or time the school changed since never reached it, nor did a room
    // announced after a course was fetched without one. These fold a later
    // lookup of the same course onto its stored row, as iOS's
    // `SDCourse.updated(with:)` does.

    /**
     * [stored] with what the school says now, from [fresh], a lookup of the
     * same course: name, instructor, credits, rooms, times, headcount,
     * dimension and term span. What the user did to it stays — the colour, the
     * rename, the manual flag — and so does its identity: the course number
     * and the Moodle ids, which the overrides and the Moodle link are keyed by.
     */
    fun refreshed(stored: Course, fresh: Course): Course = fresh.copy(
        courseNo = stored.courseNo,
        moodleIdNumber = stored.moodleIdNumber,
        moodleNumericCourseId = stored.moodleNumericCourseId,
        customColorHex = stored.customColorHex,
        isManual = stored.isManual,
        customCourseName = stored.customCourseName,
    )

    /**
     * The manual courses of [cached] a fetch has to look up itself: those
     * [rosterNos] does not list. One the roster lists is looked up with the
     * roster, and comes through [mergeFetched]'s fetched half.
     */
    fun manualNosToLookUp(cached: List<Course>, rosterNos: Collection<String>): List<String> =
        cached.filter { it.isManual && it.courseNo !in rosterNos }.map { it.courseNo }

    /**
     * [cached] with each course [lookedUp] has an answer for [refreshed], in
     * place. A course the lookup did not answer keeps its snapshot, which
     * is more than the nothing a failed query would leave.
     */
    fun withLookedUp(cached: List<Course>, lookedUp: Map<String, Course>): List<Course> =
        cached.map { course -> lookedUp[course.courseNo]?.let { refreshed(course, it) } ?: course }

    /**
     * The courses of [courses] to look up again for a room: those without one
     * that have times. One with no times either is a Moodle stub for a term
     * QueryCourse no longer indexes, and asking about it each visit would be
     * a request for nothing.
     */
    fun nosWithoutRoom(courses: List<Course>): List<String> =
        courses.filter { it.classroom.isBlank() && it.schedule.isNotEmpty() }.map { it.courseNo }

    /**
     * [courses] with the rooms [lookedUp] found: a course without a room whose
     * lookup now names one takes that row, see [refreshed]. A course that has
     * a room is not touched, nor is one whose lookup still has none. Null when
     * nothing changed, so the caller can leave the cache alone.
     */
    fun withRoomsFound(courses: List<Course>, lookedUp: Map<String, Course>): List<Course>? {
        var changed = false
        val updated = courses.map { course ->
            val found = lookedUp[course.courseNo]
                ?.takeIf { course.classroom.isBlank() && it.classroom.isNotBlank() }
                ?: return@map course
            changed = true
            refreshed(course, found)
        }
        return if (changed) updated else null
    }
}
