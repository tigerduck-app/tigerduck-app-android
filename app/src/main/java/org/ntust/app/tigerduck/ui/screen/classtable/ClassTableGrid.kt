// The timetable grid proper — weekday columns, period rows, and the
// scroll plumbing. Renders cells but does not know what a cell looks
// like; that is ClassTableCourseCell / ClassTableConflictCells.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimetableGrid(
    viewModel: ClassTableViewModel,
    weekdays: List<Int>,
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    courseNosWithAssignments: Set<String>,
    onRename: (Course) -> Unit = {},
    onDelete: (Course) -> Unit = {},
    onPickColor: (Course) -> Unit = {},
    onPickConflict: (Course, Course, Int, String) -> Unit = { _, _, _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val periodColWidth = maxOf(36.dp, (28 * fontScale).dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        val dayColWidth = (maxWidth - periodColWidth) / weekdays.size
        val totalHeight = cellHeight * periods.size

        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row {
                Box(modifier = Modifier.width(periodColWidth))
                weekdays.forEach { day ->
                    Box(
                        modifier = Modifier.width(dayColWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabels.getOrElse(day) { "$day" },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Grid body — use Box with absolute positioning so blocks can span rows
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
            ) {
                // Period labels (left column)
                periods.forEachIndexed { periodIndex, period ->
                    Column(
                        modifier = Modifier
                            .width(periodColWidth)
                            .height(cellHeight)
                            .absoluteOffset(x = 0.dp, y = cellHeight * periodIndex),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Start above, period in the middle, end below, so the
                        // row reads as the span it occupies rather than as a
                        // number with one timestamp that could be either end.
                        //
                        // Every line is pinned to one: the day cells are placed
                        // at absolute `cellHeight * index` offsets, so a label
                        // that wraps grows past its row and overlaps the next
                        // one instead of pushing it down. Clipping a wide time
                        // at a large font scale is the better failure — and
                        // ellipsis is not an option here, since "08:…" tells
                        // the reader nothing.
                        Text(
                            text = period.startTime,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        )
                        Text(
                            text = period.id,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = period.endTime,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        )
                    }
                }

                // Day cells
                weekdays.forEachIndexed { dayIndex, weekday ->
                    val x = periodColWidth + dayColWidth * dayIndex

                    periods.forEachIndexed { periodIndex, period ->
                        val y = cellHeight * periodIndex

                        when (val role = viewModel.cellRole(periods, weekday, periodIndex)) {
                            is CellRole.Empty -> {
                                Box(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight)
                                        .absoluteOffset(x = x, y = y)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = 0.3f
                                            )
                                        )
                                )
                            }

                            is CellRole.SoloStart -> {
                                SoloCourseCell(
                                    course = role.course,
                                    spanCount = role.spanCount,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    hasAssignment = role.course.courseNo in courseNosWithAssignments,
                                    onTap = {
                                        viewModel.selectCourse(
                                            role.course,
                                            weekday,
                                            period.id
                                        )
                                    },
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onDelete = onDelete,
                                )
                            }

                            is CellRole.ConflictStart -> {
                                ConflictCourseCell(
                                    cellRole = role,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    periodId = period.id,
                                    hasAssignmentA = role.courseA.courseNo in courseNosWithAssignments,
                                    hasAssignmentB = role.courseB.courseNo in courseNosWithAssignments,
                                    onPickConflict = onPickConflict,
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onDelete = onDelete,
                                )
                            }

                            is CellRole.MultiConflictStart -> {
                                MultiConflictCourseCell(
                                    cellRole = role,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    hasAssignment = { courseNo -> courseNo in courseNosWithAssignments },
                                    onSelect = { course, firstPeriodId ->
                                        viewModel.selectCourse(course, weekday, firstPeriodId)
                                    },
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onDelete = onDelete,
                                )
                            }

                            is CellRole.Skip -> {
                                // Rendered as part of an earlier SoloStart / ConflictStart
                            }
                        }
                    }
                }
            }
        }
    }
}
