// 衝堂 cells — two courses sharing a slot (ConflictCourseCell) and three
// or more (MultiConflictCourseCell). Split from the plain cell because
// the layout maths is unrelated: these divide one cell between courses
// and still have to stay tappable per-course at grid density.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ConflictLOrientation
import org.ntust.app.tigerduck.ui.component.ConflictLShape
import org.ntust.app.tigerduck.ui.component.isEnglishUiLanguage
import org.ntust.app.tigerduck.ui.component.middleEllipsize
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConflictCourseCell(
    cellRole: CellRole.ConflictStart,
    dayColWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    weekday: Int,
    periodId: String,
    hasAssignmentA: Boolean,
    hasAssignmentB: Boolean,
    onPickConflict: (Course, Course, Int, String) -> Unit,
    onLongPress: () -> Unit,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onDelete: (Course) -> Unit,
) {
    val textColor = if (TigerDuckTheme.isDarkMode) Color.White else Color(0xFF1C1C1E)
    fun bgFor(course: Course) = if (TigerDuckTheme.isDarkMode) {
        TigerDuckTheme.courseColor(course.courseNo)
    } else {
        TigerDuckTheme.courseColorVibrant(course.courseNo).copy(alpha = 0.50f)
    }

    val overlapStart = maxOf(cellRole.offsetA, cellRole.offsetB)
    val overlapEnd = minOf(cellRole.offsetA + cellRole.spanA, cellRole.offsetB + cellRole.spanB)

    // Solo fractions are relative to each course's OWN span (= its Box height),
    // not the combined span.
    fun soloAbove(offset: Int, span: Int) =
        (overlapStart - offset).coerceAtLeast(0).toFloat() / span

    fun soloBelow(offset: Int, span: Int) =
        (offset + span - overlapEnd).coerceAtLeast(0).toFloat() / span

    val soloAboveA = soloAbove(cellRole.offsetA, cellRole.spanA)
    val soloBelowA = soloBelow(cellRole.offsetA, cellRole.spanA)
    val soloAboveB = soloAbove(cellRole.offsetB, cellRole.spanB)
    val soloBelowB = soloBelow(cellRole.offsetB, cellRole.spanB)

    // Pure overlap at an edge = neither course has solo at that edge. There
    // both shapes have convex corners pointing the same way, so only sharp
    // corners can touch without a gap.
    val sharpTop = soloAboveA == 0f && soloAboveB == 0f
    val sharpBottom = soloBelowA == 0f && soloBelowB == 0f

    val shapeA = ConflictLShape(
        orientation = ConflictLOrientation.TopBarRightTail,
        soloAboveFraction = soloAboveA,
        soloBelowFraction = soloBelowA,
        sharpTopOuter = sharpTop,
        sharpBottomOuter = sharpBottom,
    )
    val shapeB = ConflictLShape(
        orientation = ConflictLOrientation.LeftTailBottomBar,
        soloAboveFraction = soloAboveB,
        soloBelowFraction = soloBelowB,
        sharpTopOuter = sharpTop,
        sharpBottomOuter = sharpBottom,
    )

    var showMenu by remember { mutableStateOf(false) }
    // Shared so a press on EITHER course triggers the ripple on BOTH — the
    // menu applies to both, so the press feedback should too.
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * cellRole.combinedSpan)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp),
    ) {
        // Pull the true padded size so child heights/offsets scale to it exactly.
        // Using a fixed `cellHeight * spanA` for each child causes its Box to
        // exceed the padded area by 2dp; Compose's overflow handling then
        // leaves a visible seam between the two shapes.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rowHeight = maxHeight / cellRole.combinedSpan
            val aTop = rowHeight * cellRole.offsetA
            val aHeight = rowHeight * cellRole.spanA
            val bTop = rowHeight * cellRole.offsetB
            val bHeight = rowHeight * cellRole.spanB
            val aBarFraction = (soloAboveA + 0.5f * (1f - soloAboveA - soloBelowA))
                .coerceAtLeast(0.1f)
            val bBarFraction = (soloBelowB + 0.5f * (1f - soloAboveB - soloBelowB))
                .coerceAtLeast(0.1f)
            val conflictPrefix = stringResource(R.string.a11y_class_table_conflict_prefix)
            val assignmentLabel =
                stringResource(R.string.a11y_class_table_cell_assignment_indicator)

            fun cellLabel(course: Course, hasAssignment: Boolean): String = buildString {
                append(conflictPrefix)
                append(": ")
                append(course.displayName)
                val room = course.classroom(weekday)
                if (room.isNotBlank()) {
                    append(", ")
                    append(room)
                }
                if (hasAssignment) {
                    append(". ")
                    append(assignmentLabel)
                }
            }

            val labelA = cellLabel(cellRole.courseA, hasAssignmentA)
            val labelB = cellLabel(cellRole.courseB, hasAssignmentB)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(aHeight)
                    .absoluteOffset(x = 0.dp, y = aTop)
                    .clip(shapeA)
                    .background(bgFor(cellRole.courseA))
                    .semantics(mergeDescendants = true) {
                        contentDescription = labelA
                        role = Role.Button
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = indication,
                        onClick = {
                            onPickConflict(
                                cellRole.courseA,
                                cellRole.courseB,
                                weekday,
                                periodId
                            )
                        },
                        onLongClick = { onLongPress(); showMenu = true },
                    ),
            ) {
                // Course name uses the full width of the bar rectangle.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(1f - 0.28f)
                        .fillMaxHeight(aBarFraction)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ClassTableCourseNameText(
                        text = cellRole.courseA.displayName,
                        color = textColor,
                        maxLines = 2,
                    )
                }
                if (hasAssignmentA) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(10.dp),
                        tint = textColor.copy(alpha = 0.7f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bHeight)
                    .absoluteOffset(x = 0.dp, y = bTop)
                    .clip(shapeB)
                    .background(bgFor(cellRole.courseB))
                    .semantics(mergeDescendants = true) {
                        contentDescription = labelB
                        role = Role.Button
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = indication,
                        onClick = {
                            onPickConflict(
                                cellRole.courseA,
                                cellRole.courseB,
                                weekday,
                                periodId
                            )
                        },
                        onLongClick = { onLongPress(); showMenu = true },
                    ),
            ) {
                // Course name uses the full width of the bar rectangle.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(1f - 0.28f)
                        .fillMaxHeight(bBarFraction)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ClassTableCourseNameText(
                        text = cellRole.courseB.displayName,
                        color = textColor,
                        maxLines = 2,
                    )
                }
                if (hasAssignmentB) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(3.dp)
                            .size(10.dp),
                        tint = textColor.copy(alpha = 0.7f),
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                shape = RoundedCornerShape(12.dp),
            ) {
                listOf(cellRole.courseA, cellRole.courseB).forEachIndexed { idx, course ->
                    if (idx > 0) HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_rename_with_course,
                                    course.displayName,
                                )
                            )
                        },
                        onClick = { showMenu = false; onRename(course) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_pick_color_with_course,
                                    course.displayName,
                                )
                            )
                        },
                        onClick = { showMenu = false; onPickColor(course) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_delete_with_course,
                                    course.displayName,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showMenu = false; onDelete(course) },
                    )
                }
            }
        } // BoxWithConstraints
    }
}

/**
 * Renders a cluster of 3+ transitively-overlapping courses as vertical lanes —
 * each course occupies one column for its own row range, with greedy lane
 * coloring (see [ClassTableViewModel.cellRole]) packing non-overlapping
 * courses into the same lane so a chain like A(6-7) / B(6-8) / C(8-9) fits in
 * two columns instead of three. The 2-course L-shape ([ConflictCourseCell])
 * can't tile 3 shapes, so this is the fallback. Tapping a lane selects that
 * specific course directly (no picker), matching the home slider's per-band
 * behavior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MultiConflictCourseCell(
    cellRole: CellRole.MultiConflictStart,
    dayColWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    weekday: Int,
    hasAssignment: (String) -> Boolean,
    onSelect: (Course, String) -> Unit,
    onLongPress: () -> Unit,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onDelete: (Course) -> Unit,
) {
    val textColor = if (TigerDuckTheme.isDarkMode) Color.White else Color(0xFF1C1C1E)
    fun bgFor(course: Course) = if (TigerDuckTheme.isDarkMode) {
        TigerDuckTheme.courseColor(course.courseNo)
    } else {
        TigerDuckTheme.courseColorVibrant(course.courseNo).copy(alpha = 0.50f)
    }

    val conflictPrefix = stringResource(R.string.a11y_class_table_conflict_prefix)
    val assignmentLabel = stringResource(R.string.a11y_class_table_cell_assignment_indicator)
    fun cellLabel(course: Course, hasAssignmentForCourse: Boolean): String = buildString {
        append(conflictPrefix)
        append(": ")
        append(course.displayName)
        val room = course.classroom(weekday)
        if (room.isNotBlank()) {
            append(", ")
            append(room)
        }
        if (hasAssignmentForCourse) {
            append(". ")
            append(assignmentLabel)
        }
    }

    var menuForCourse by remember { mutableStateOf<Course?>(null) }

    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * cellRole.combinedSpan)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rowHeight = maxHeight / cellRole.combinedSpan
            val laneWidth = maxWidth / cellRole.laneCount

            cellRole.members.forEach { member ->
                val mTop = rowHeight * member.offset
                val mHeight = rowHeight * member.span
                val mLeft = laneWidth * member.lane
                val hasAssignmentForMember = hasAssignment(member.course.courseNo)

                Box(
                    modifier = Modifier
                        .width(laneWidth)
                        .height(mHeight)
                        .absoluteOffset(x = mLeft, y = mTop)
                        .padding(0.5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bgFor(member.course))
                        .semantics(mergeDescendants = true) {
                            contentDescription = cellLabel(member.course, hasAssignmentForMember)
                            role = Role.Button
                        }
                        .combinedClickable(
                            onClick = { onSelect(member.course, member.firstPeriodId) },
                            onLongClick = {
                                onLongPress()
                                menuForCourse = member.course
                            },
                        ),
                ) {
                    ClassTableCourseNameText(
                        text = member.course.displayName,
                        color = textColor,
                        maxLines = if (member.span >= 2) 3 else 2,
                        modifier = Modifier
                            .padding(2.dp)
                            .align(Alignment.Center),
                    )
                    if (hasAssignmentForMember) {
                        Icon(
                            imageVector = Icons.Filled.Book,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .size(10.dp),
                            tint = textColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            menuForCourse?.let { course ->
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuForCourse = null },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_rename_with_course,
                                    course.displayName,
                                )
                            )
                        },
                        onClick = { menuForCourse = null; onRename(course) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_pick_color_with_course,
                                    course.displayName,
                                )
                            )
                        },
                        onClick = { menuForCourse = null; onPickColor(course) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_delete_with_course,
                                    course.displayName,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { menuForCourse = null; onDelete(course) },
                    )
                }
            }
        }
    }
}
