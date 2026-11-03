// Folding a freshly fetched timetable back onto the one already on disk.
//
// The fetch knows the school's view: schedule, room, credits, instructor. It
// knows nothing about what the user did to that view — the colour they
// picked, the course they typed in by hand, the name they rewrote, the course
// they deleted. Each of those has to be carried across explicitly, and each
// one that is missed is a user-visible regression on the next refresh.
//
// Home (HomeCourseMerge.mergeRemote) and the background sync fold their
// fetches through this as well: all three write the same cache file, and a
// rule only one of them kept, the manual flag or a hand-add's missing Moodle
// ID, made a course's survival depend on which refreshed last.

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
     * the school's roster has never heard of them, and any other course it
     * does not mention that [alsoKeeps] says stays: every one, for a term that
     * only gains from the transcript (tigerduck-app#890), whose answers do not
     * list a course another fetch saved while its lookups ran.
     *
     * Dropped last: anything in [deletedNos], applied to the merged list so a
     * deleted course cannot re-enter through either half.
     *
     * Home ([HomeCourseMerge][org.ntust.app.tigerduck.ui.screen.home.HomeCourseMerge])
     * and the background sync merge through this too: the three write the
     * same file.
     */
    fun mergeFetched(
        fetched: List<Course>,
        cached: List<Course>,
        names: CustomNameMap,
        locale: String,
        deletedNos: Set<String>,
        alsoKeeps: (Course) -> Boolean = { false },
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
        val leftovers = cached.filter { it.courseNo !in fetchedNos && (it.isManual || alsoKeeps(it)) }
        return CourseNameOverrides.resolve(restored + leftovers, names, locale)
            .filter { it.courseNo !in deletedNos }
    }

    /** The course numbers an edit of the list from [before] to [after] added, took off or changed. */
    fun editedNos(before: List<Course>, after: List<Course>): Set<String> {
        val was = before.toSet()
        val now = after.toSet()
        return (before.filterNot { it in now } + after.filterNot { it in was }).mapTo(mutableSetOf()) { it.courseNo }
    }

    /**
     * [stored], the term's list as it stands, with what an edit of [before], the list on screen
     * at the tap, into [after] changed, and nothing else (race 5, refactor-v4 R6 and R8): a course
     * the edit added goes in, one it took off goes, and one it changed takes the edit's copy, while
     * every course it did not touch stays as stored. So a pull's colours or names and another
     * device's course, or a newer roster a fetch wrote since the tap, are not written back as the
     * tap found them; and a changed course the term no longer has is not brought back.
     */
    fun editOnto(stored: List<Course>, before: List<Course>, after: List<Course>): List<Course> {
        val edited = editedNos(before, after)
        if (edited.isEmpty()) return stored
        val was = before.associateBy { it.courseNo }
        val now = after.associateBy { it.courseNo }
        val kept = stored.mapNotNull { course -> if (course.courseNo in edited) now[course.courseNo] else course }
        val storedNos = stored.mapTo(mutableSetOf()) { it.courseNo }
        return kept + after.filter { it.courseNo in edited && it.courseNo !in storedNos && it.courseNo !in was }
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
