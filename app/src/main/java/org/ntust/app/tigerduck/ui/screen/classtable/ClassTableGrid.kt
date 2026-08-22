// The timetable grid proper — weekday columns, period rows, and the
// scroll plumbing. Renders cells but does not know what a cell looks
// like; that is ClassTableCourseCell / ClassTableConflictCells.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.isEnglishUiLanguage
import org.ntust.app.tigerduck.ui.component.middleEllipsize
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

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
    val cellHeight = 52.dp
    val periodColWidth = 36.dp

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
                        Text(
                            text = period.id,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            fontSize = 12.sp
                        )
                        Text(
                            text = period.startTime,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                        )
                    }
                }

                // Day cells
                weekdays.forEachIndexed { dayIndex, weekday ->
                    val x = periodColWidth + dayColWidth * dayIndex

                    periods.forEachIndexed { periodIndex, period ->
                        val y = cellHeight * periodIndex

                        when (val role = viewModel.cellRole(weekday, periodIndex)) {
                            is ClassTableViewModel.CellRole.Empty -> {
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

                            is ClassTableViewModel.CellRole.SoloStart -> {
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

                            is ClassTableViewModel.CellRole.ConflictStart -> {
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

                            is ClassTableViewModel.CellRole.MultiConflictStart -> {
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

                            is ClassTableViewModel.CellRole.Skip -> {
                                // Rendered as part of an earlier SoloStart / ConflictStart
                            }
                        }
                    }
                }
            }
        }
    }
}
