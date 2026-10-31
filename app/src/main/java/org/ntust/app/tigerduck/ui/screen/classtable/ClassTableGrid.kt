// The timetable grid proper — weekday columns, period rows, and the
// scroll plumbing. Renders cells but does not know what a cell looks
// like; that is ClassTableCourseCell / ClassTableConflictCells.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.timeline.ClassSlot
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimetableGrid(
    /**
     * The collected course list, not `viewModel.courses.value`: a plain read
     * of the StateFlow registers no dependency, so a course added into rows
     * and days already on screen left every other input here unchanged and
     * the grid was skipped until the next restart.
     */
    courses: List<Course>,
    /** Print each course's room in its cell's corner; see [CourseRoomHint]. */
    showRoomHints: Boolean,
    weekdays: List<Int>,
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    courseNosWithAssignments: Set<String>,
    /** Courses whose cells carry the ignored mark; none in the exported image. */
    courseNosIgnoringAssignments: Set<String> = emptySet(),
    /** Outline each required course's cell; see [AppPreferences.outlineRequiredCourses]. */
    outlineRequiredCourses: Boolean = false,
    onRename: (Course) -> Unit = {},
    onDelete: (Course) -> Unit = {},
    onPickColor: (Course) -> Unit = {},
    /** Null leaves the action out of the long-press menu. */
    onToggleIgnoreAssignments: ((Course) -> Unit)? = null,
    onPickConflict: (Course, Course, Int, String) -> Unit = { _, _, _, _ -> },
    /** A tap on a course: the course, its weekday and the period tapped. None in an exported image. */
    onSelect: (course: Course, weekday: Int, periodId: String) -> Unit = { _, _, _ -> },
    /** False on someone else's class table, whose courses have no long-press menu. */
    editable: Boolean = true,
    /**
     * Where the period rows' block was placed, and each row's height in pixels; the rows are
     * [periods] in order from its top. The page keeps the rows in place across a switch with it.
     */
    onRowsPlaced: ((rows: LayoutCoordinates, rowHeightPx: Float) -> Unit)? = null,
    /** The week from today whose course changes the blocks show; null for a plain timetable. */
    thisWeek: ClassTableWeek? = null,
    /** The minute of the day the current-time line marks; null for none. */
    nowMinute: Int? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The app's own long-press vibration, through the Vibration setting; none without a menu.
    val onLongPress: (() -> Unit)? = if (editable) {
        { Haptics.perform(context, HapticScenario.ClassTableLongPress) }
    } else {
        null
    }
    val dayLabels = listOf(
        "",
        stringResource(R.string.weekday_mon_short),
        stringResource(R.string.weekday_tue_short),
        stringResource(R.string.weekday_wed_short),
        stringResource(R.string.weekday_thu_short),
        stringResource(R.string.weekday_fri_short),
        stringResource(R.string.weekday_sat_short),
        stringResource(R.string.weekday_sun_short),
    )
    // The period column stacks three lines (start / 節 / end) totalling 36sp
    // of line height. That leaves comfortable slack in a 52dp row at the
    // default font scale, but the rows are placed at absolute
    // `cellHeight * index` offsets — so once the text outgrows the row it
    // does not push the next one down, it overlaps it. Grow the row with the
    // system font scale instead: unchanged up to ~1.3x, taller beyond, which
    // is what someone who asked for bigger text wants anyway.
    val fontScale = LocalDensity.current.fontScale
    val cellHeight = maxOf(52.dp, (40 * fontScale).dp)
    val style = LocalVisualStylePolicy.current
    val periodColWidth = style.classTablePeriodColumnWidth(fontScale)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // Outside the padding, so the bands run from edge to edge.
            .classTableRowBands(style, MaterialTheme.colorScheme.onSurface, periods.size, cellHeight)
            .padding(horizontal = 4.dp)
    ) {
        val dayColWidth = (maxWidth - periodColWidth) / weekdays.size
        val totalHeight = cellHeight * periods.size

        Column(modifier = Modifier.fillMaxWidth()) {
            ClassTableWeekdayHeader(weekdays, dayLabels, periodColWidth, dayColWidth, today = thisWeek?.todayWeekday)

            // Grid body — use Box with absolute positioning so blocks can span rows.
            // Offsets from the top left in either direction: a Box aligns to the
            // start, which in a right-to-left language put every block at the right
            // edge before its offset pushed it off the screen.
            val rowHeightPx = with(LocalDensity.current) { cellHeight.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .then(
                        if (onRowsPlaced == null) Modifier
                        else Modifier.onGloballyPositioned { onRowsPlaced(it, rowHeightPx) }
                    ),
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                // Period labels (left column)
                periods.forEachIndexed { periodIndex, period ->
                    ClassTablePeriodLabel(
                        period = period,
                        cellHeight = cellHeight,
                        modifier = Modifier
                            .width(periodColWidth)
                            .absoluteOffset(x = 0.dp, y = cellHeight * periodIndex),
                    )
                }

                // What each slot shows, worked out once for both passes below.
                val roles = remember(courses, periods, weekdays) {
                    weekdays.map { weekday ->
                        periods.indices.map { ClassTableCellLayout.roleAt(courses, periods, weekday, it) }
                    }
                }

                // Empty slots first, drawn at once: a course fading out of a
                // slot sits above them, and the grid itself never fades.
                weekdays.forEachIndexed { dayIndex, _ ->
                    periods.forEachIndexed { periodIndex, _ ->
                        if (roles[dayIndex][periodIndex] is CellRole.Empty) {
                            ClassTableEmptySlot(
                                modifier = Modifier
                                    .width(dayColWidth)
                                    .height(cellHeight)
                                    .absoluteOffset(x = periodColWidth + dayColWidth * dayIndex, y = cellHeight * periodIndex)
                            )
                        }
                    }
                }

                // Then the courses, each slot keyed by its day and period so
                // a switch fades only the slots whose courses change.
                weekdays.forEachIndexed { dayIndex, weekday ->
                    val x = periodColWidth + dayColWidth * dayIndex

                    periods.forEachIndexed { periodIndex, period ->
                        val y = cellHeight * periodIndex
                        val role = roles[dayIndex][periodIndex]
                        // Solo cells only: a 衝堂 cluster splits the cell and
                        // leaves no corner to print in.
                        val roomHint = if (showRoomHints && role is CellRole.SoloStart) {
                            CourseRoomHint.room(role.course, weekday, period.id)
                        } else {
                            null
                        }
                        // Solo blocks only, as on iOS: a 衝堂 cluster keeps the weekly look.
                        val change = if (thisWeek != null && role is CellRole.SoloStart) {
                            thisWeek.meeting(
                                role.course, weekday, periods.drop(periodIndex).take(role.spanCount).map { it.id },
                            )
                        } else {
                            null
                        }

                        key(weekday, period.id) {
                            CourseSlotFade(
                                target = SlotCourses(
                                    role, roomHint, courseNosWithAssignments, courseNosIgnoringAssignments, change,
                                ),
                                key = { ClassTableCellLayout.contentKey(it.role, it.roomHint) },
                                modifier = Modifier.absoluteOffset(x = x, y = y),
                            ) { slot ->
                                CourseSlot(
                                    role = slot.role,
                                    weekday = weekday,
                                    periodId = period.id,
                                    periods = periods,
                                    periodIndex = periodIndex,
                                    roomHint = slot.roomHint,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    onSelect = onSelect,
                                    courseNosWithAssignments = slot.withAssignments,
                                    courseNosIgnoringAssignments = slot.ignoringAssignments,
                                    outlineRequiredCourses = outlineRequiredCourses,
                                    onLongPress = onLongPress,
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onToggleIgnoreAssignments = onToggleIgnoreAssignments,
                                    onDelete = onDelete,
                                    onPickConflict = onPickConflict,
                                    change = slot.change,
                                    showsRooms = showRoomHints,
                                )
                            }
                        }
                    }
                }

                val today = thisWeek?.todayWeekday?.let(weekdays::indexOf)?.takeIf { it >= 0 }
                if (today != null && nowMinute != null) {
                    ClassTableNowLine(
                        minute = nowMinute,
                        periods = periods,
                        cellHeight = cellHeight,
                        x = periodColWidth,
                        width = dayColWidth * weekdays.size,
                    )
                }
            }
        }
    }
}

/**
 * The weekday labels over the grid's day columns, [dayLabels] indexed by weekday. Placed the way
 * the cells are, at absolute offsets from the left: a `Row` mirrors in a right-to-left language,
 * and put each label over another day's courses.
 */
@Composable
private fun ClassTableWeekdayHeader(
    weekdays: List<Int>,
    dayLabels: List<String>,
    periodColWidth: Dp,
    dayColWidth: Dp,
    /** Today's weekday in a week from today, whose label alone takes the accent; null for a plain timetable. */
    today: Int? = null,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = AbsoluteAlignment.TopLeft) {
        weekdays.forEachIndexed { dayIndex, day ->
            Box(
                modifier = Modifier
                    .width(dayColWidth)
                    .absoluteOffset(x = periodColWidth + dayColWidth * dayIndex),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayLabels.getOrElse(day) { "$day" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (today == null || day == today) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * What one slot shows: the courses that start in it, the room printed in its corner and the
 * marks, kept with them so courses fading out keep the marks they had.
 */
private data class SlotCourses(
    val role: CellRole,
    val roomHint: String?,
    val withAssignments: Set<String>,
    val ignoringAssignments: Set<String>,
    /** A solo block as the school changed it on its column's date. */
    val change: ClassSlot? = null,
)

/**
 * The current-time line over the day columns, with a dot at its leading end, as on iOS: inside a
 * period by the share of it gone, at the top of the next between two; none before the first or
 * after the last.
 */
@Composable
private fun ClassTableNowLine(
    minute: Int,
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    cellHeight: Dp,
    x: Dp,
    width: Dp,
) {
    val ranges = periods.map { minuteOf(it.startTime)..minuteOf(it.endTime) }
    val offset = ClassTableThisWeek.nowLineOffset(minute, ranges, cellHeight.value, cellHeight.value) ?: return
    val color = MaterialTheme.colorScheme.primary
    val dot = 7.dp
    val line = 1.5.dp
    Box(
        modifier = Modifier
            .absoluteOffset(x = x - dot / 2, y = offset.dp - dot / 2)
            .width(width + dot / 2)
            .height(dot)
            .testTag(NOW_LINE_TAG)
            .clearAndSetSemantics {},
    ) {
        Box(
            Modifier
                .absoluteOffset(y = (dot - line) / 2)
                .fillMaxWidth()
                .height(line)
                .background(color),
        )
        Box(Modifier.size(dot).background(color, CircleShape))
    }
}

private fun minuteOf(hm: String): Int = hm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

/** The courses that start in one slot, drawn from the slot's own corner; the grid places it. */
@Composable
private fun CourseSlot(
    role: CellRole,
    weekday: Int,
    periodId: String,
    /** The table's periods and the slot's place in them, which a 衝堂 cluster reads its rows from. */
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    periodIndex: Int,
    roomHint: String?,
    dayColWidth: Dp,
    cellHeight: Dp,
    onSelect: (course: Course, weekday: Int, periodId: String) -> Unit,
    courseNosWithAssignments: Set<String>,
    courseNosIgnoringAssignments: Set<String>,
    outlineRequiredCourses: Boolean,
    onLongPress: (() -> Unit)?,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onToggleIgnoreAssignments: ((Course) -> Unit)?,
    onDelete: (Course) -> Unit,
    onPickConflict: (Course, Course, Int, String) -> Unit,
    change: ClassSlot?,
    showsRooms: Boolean,
) {
    when (role) {
        is CellRole.SoloStart -> SoloCourseCell(
            course = role.course,
            roomHint = roomHint,
            spanCount = role.spanCount,
            dayColWidth = dayColWidth,
            cellHeight = cellHeight,
            x = 0.dp,
            y = 0.dp,
            weekday = weekday,
            periodId = periodId,
            hasAssignment = role.course.courseNo in courseNosWithAssignments,
            assignmentsIgnored = role.course.courseNo in courseNosIgnoringAssignments,
            outlineRequired = outlineRequiredCourses,
            onTap = { onSelect(role.course, weekday, periodId) },
            onLongPress = onLongPress,
            onRename = onRename,
            onPickColor = onPickColor,
            onToggleIgnoreAssignments = onToggleIgnoreAssignments,
            onDelete = onDelete,
            change = change,
            showsRooms = showsRooms,
        )

        is CellRole.ConflictStart -> ConflictCourseCell(
            cellRole = role,
            dayColWidth = dayColWidth,
            cellHeight = cellHeight,
            x = 0.dp,
            y = 0.dp,
            weekday = weekday,
            periodId = periodId,
            clusterPeriods = periods
                .drop(periodIndex)
                .take(role.combinedSpan)
                .map { it.id },
            hasAssignmentA = role.courseA.courseNo in courseNosWithAssignments,
            hasAssignmentB = role.courseB.courseNo in courseNosWithAssignments,
            assignmentsIgnoredA = role.courseA.courseNo in courseNosIgnoringAssignments,
            assignmentsIgnoredB = role.courseB.courseNo in courseNosIgnoringAssignments,
            outlineRequired = outlineRequiredCourses,
            onPickConflict = onPickConflict,
            onLongPress = onLongPress,
            onRename = onRename,
            onPickColor = onPickColor,
            onToggleIgnoreAssignments = onToggleIgnoreAssignments,
            onDelete = onDelete,
        )

        is CellRole.MultiConflictStart -> MultiConflictCourseCell(
            cellRole = role,
            dayColWidth = dayColWidth,
            cellHeight = cellHeight,
            x = 0.dp,
            y = 0.dp,
            weekday = weekday,
            hasAssignment = { courseNo -> courseNo in courseNosWithAssignments },
            assignmentsIgnored = { courseNo -> courseNo in courseNosIgnoringAssignments },
            outlineRequired = outlineRequiredCourses,
            onSelect = { course, firstPeriodId -> onSelect(course, weekday, firstPeriodId) },
            onLongPress = onLongPress,
            onRename = onRename,
            onPickColor = onPickColor,
            onToggleIgnoreAssignments = onToggleIgnoreAssignments,
            onDelete = onDelete,
        )

        // Rendered as part of an earlier start, or an empty slot drawn under the courses.
        CellRole.Empty, CellRole.Skip -> Unit
    }
}

