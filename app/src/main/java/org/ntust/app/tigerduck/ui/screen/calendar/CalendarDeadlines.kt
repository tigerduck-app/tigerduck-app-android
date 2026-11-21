package org.ntust.app.tigerduck.ui.screen.calendar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.shared.time.TaipeiClock

/**
 * [base] with its Moodle rows made from [assignments], the assignment cache's,
 * and none when it holds none: an answer from Moodle with no assignments leaves it
 * empty, and the rows of the ones it had must go with them (issue #160). An
 * assignment with no due date has no row (tigerduck-app#1029).
 */
internal fun withMoodleRows(
    base: List<CalendarEvent>,
    assignments: List<Assignment>,
): List<CalendarEvent> =
    base.filterNot { it.sourceRaw == EventSource.MOODLE.raw } + assignments.toCalendarEvents()

private fun List<Assignment>.toCalendarEvents(): List<CalendarEvent> =
    mapNotNull { assignment ->
        CalendarEvent(
            eventId = CalendarEvent.moodleEventId(assignment.assignmentId),
            title = assignment.title,
            date = assignment.studentDueDate ?: return@mapNotNull null,
            sourceRaw = EventSource.MOODLE.raw
        )
    }

/**
 * The assignments whose deadline rows open their page, as the iPhone's calendar opens them
 * (tigerduck-app#999): those of the courses the class table shows, [shownCourseNos], or every
 * one when it shows none, as the iPhone's Home then lists them all. Each is one of [listed], the
 * assignment cache's rows, which the page reads beside the last round's records, so the page
 * finds it: with no record it builds one from the row (`MoodleAssignmentEntries.of`). A row the
 * calendar still shows after its assignment left the cache opens nothing, where the page would
 * open and close at once.
 */
internal fun openableAssignmentIds(listed: List<Assignment>, shownCourseNos: Set<String>): Set<String> =
    listed.filter { shownCourseNos.isEmpty() || it.courseNo in shownCourseNos }.mapTo(mutableSetOf()) { it.assignmentId }

/**
 * [openableAssignmentIds] of [cache] as it is now: its assignments, and the courses the class
 * table shows this term, those it holds less those hidden in it.
 */
internal suspend fun openableAssignmentIds(cache: DataCache): Set<String> {
    val semester = cache.currentSemesterCode()
    val hidden = CourseTombstoneKeys.hiddenIn(semester, cache.loadDeletedCourseNos())
    val shown = cache.loadCourses(semester).mapNotNullTo(mutableSetOf()) { course -> course.courseNo.takeIf { it !in hidden } }
    return openableAssignmentIds(cache.loadAssignments(), shown)
}

/**
 * The term whose class table counts, read again on each minute of the app's clock and each
 * calendar [published], so a term that turns while the calendar is open counts its own courses.
 */
internal fun DataCache.currentTerms(published: Flow<*>): Flow<String> =
    merge(TaipeiClock.minuteTicks(), published).map { currentSemesterCode() }.distinctUntilChanged()

/** The assignment this row opens: a deadline's, when it is one of [openable]; null for any other row. */
internal fun CalendarEvent.assignmentToOpen(openable: Set<String>): String? = moodleAssignmentId?.takeIf { it in openable }
