package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.Course

/**
 * The kind a course delete names, `DELETE /sync/courses/{course_key}?kind=` (tigerduck-app-android#698).
 * With none, the backend removes a `user_added` row and hides any other key, one it holds no row of
 * too: a course added by hand whose add had not reached it came back hidden on every device, the
 * school's copies included.
 */
enum class CourseDeleteKind(val wire: String) {
    /** A hide, of a `user_added` row too: the course stays out on every device. */
    HIDE("hide"),

    /** A hand-add taken back: no device hides the course, and a school row stays, only losing the add. */
    REMOVE("remove");

    companion object {
        /**
         * The kind of a delete, as the iPhone's `OutboxSender` picks it (tigerduck-app#985): [REMOVE]
         * for a course this phone does not hide ([hiddenHere]), or hides only as one added by hand
         * here ([addedHere]), which the iPhone does not hide at all; [HIDE] for one hidden here that
         * the school lists for this student ([listedBySchool]); none for any other, such as a copy
         * merged down from another device's row, whose row on the backend decides.
         */
        fun of(hiddenHere: Boolean, addedHere: Boolean, listedBySchool: Boolean): CourseDeleteKind? = when {
            !hiddenHere -> REMOVE
            listedBySchool -> HIDE
            addedHere -> REMOVE
            else -> null
        }

        /**
         * The kind of the class table's delete of [course] as the term holds it before the delete
         * writes anything, leaving a hide here when [hides]: [listedBySchool] from the school's
         * lists ([schoolLists]), which the delete's own save takes a school course off.
         */
        fun asked(course: Course?, hides: Boolean, listedBySchool: Boolean): CourseDeleteKind? = of(
            hiddenHere = hides,
            addedHere = course != null && TranscriptTerms.isAddedHere(course),
            listedBySchool = listedBySchool || course?.isManual == false,
        )
    }
}

/** A delete's kind as chosen when it was asked, none included, for the sends of that delete. */
data class ChosenKind(val kind: CourseDeleteKind?)

/** Whether the school lists [courseNo] in [semester] for this student: its cached rows, or course selection's answer. */
suspend fun DataCache.schoolLists(semester: String, courseNo: String): Boolean =
    courseNo in portalCourseNos(semester) || loadSelectionRosters()[semester]?.contains(courseNo) == true

/**
 * The kind of a delete no choice was kept for ([ChosenKind]), one an older build queued or one
 * kept before this process, read as it goes ([CourseDeleteKind.of]): from the hides, the mark
 * [DataCache.hideDeletedCourse] gives a course added here that course selection did not list, and
 * the school's lists.
 */
suspend fun DataCache.courseDeleteKind(semester: String, courseNo: String): CourseDeleteKind? =
    CourseDeleteKind.of(
        hiddenHere = CourseTombstoneKeys.isHidden(courseNo, semester, loadDeletedCourseNos()),
        addedHere = CourseTombstoneKeys.key(semester, courseNo) in loadHandAddedHides(),
        listedBySchool = schoolLists(semester, courseNo),
    )
