package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.replaceIosArg

/**
 * What TalkBack reads for a course's tile in the class table: the weekday and
 * period first, then the course, the room and its assignment marks, as iOS
 * reads `a11y_timetable_cell`. Without the day and period the same course on
 * Monday and Wednesday sounded identical.
 *
 * Built from [classTableCellLabels], so each cell resolves its strings
 * once rather than once per course it holds.
 */
internal class ClassTableCellLabels(
    private val template: String,
    /** Short weekday names, Monday first: the same the grid heads its columns with. */
    private val weekdays: List<String>,
    private val conflictPrefix: String,
    private val assignment: String,
    private val ignored: String,
) {
    /**
     * The label of [course]'s tile on [weekday] starting at [periodId]. Without a period, the
     * label is the course, the day's rooms and the marks alone, as it read before tiles named
     * their period.
     */
    fun label(
        course: Course,
        weekday: Int,
        periodId: String?,
        hasAssignment: Boolean,
        assignmentsIgnored: Boolean,
        conflict: Boolean = false,
    ): String {
        val spoken = buildString {
            append(course.displayName)
            // The room of this slot, as tapping it shows, not every room the
            // course meets in that day.
            val room = if (periodId != null) course.classroom(weekday, periodId) else course.classroom(weekday)
            if (room.isNotBlank()) append(", ").append(room)
            if (hasAssignment) append(". ").append(assignment)
            if (assignmentsIgnored) append(". ").append(ignored)
        }
        val cell = if (periodId == null) {
            spoken
        } else {
            template
                .replaceIosArg(1, weekdays.getOrElse(weekday - 1) { weekday.toString() })
                .replaceIosArg(2, periodId)
                .replaceIosArg(3, spoken)
        }
        return if (conflict) "$conflictPrefix: $cell" else cell
    }
}

@Composable
internal fun classTableCellLabels(): ClassTableCellLabels = ClassTableCellLabels(
    template = stringResource(R.string.a11y_timetable_cell),
    weekdays = listOf(
        stringResource(R.string.weekday_mon_short),
        stringResource(R.string.weekday_tue_short),
        stringResource(R.string.weekday_wed_short),
        stringResource(R.string.weekday_thu_short),
        stringResource(R.string.weekday_fri_short),
        stringResource(R.string.weekday_sat_short),
        stringResource(R.string.weekday_sun_short),
    ),
    conflictPrefix = stringResource(R.string.a11y_class_table_conflict_prefix),
    assignment = stringResource(R.string.a11y_class_table_cell_assignment_indicator),
    ignored = stringResource(R.string.a11y_class_table_cell_assignments_ignored),
)
