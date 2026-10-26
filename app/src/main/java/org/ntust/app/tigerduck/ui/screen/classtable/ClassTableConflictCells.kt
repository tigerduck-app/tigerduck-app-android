// 衝堂 cells — two courses sharing a slot (ConflictCourseCell) and three
// or more (MultiConflictCourseCell). Split from the plain cell because
// the layout maths is unrelated: these divide one cell between courses
// and still have to stay tappable per-course at grid density.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.indication
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ConflictLOrientation
import org.ntust.app.tigerduck.ui.component.ConflictLShape
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy

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
    /**
     * The period of each of the cluster's rows, from its first, so each course's label names
     * the period its own block starts at and that period's room. Empty names [periodId] for both.
     */
    clusterPeriods: List<String> = emptyList(),
    hasAssignmentA: Boolean,
    hasAssignmentB: Boolean,
    assignmentsIgnoredA: Boolean,
    assignmentsIgnoredB: Boolean,
    /** Whether a required course's shape is outlined; see [AppPreferences.outlineRequiredCourses]. */
    outlineRequired: Boolean = false,
    onPickConflict: (Course, Course, Int, String) -> Unit,
    /** Null on someone else's class table, which is read-only and has no menu. */
    onLongPress: (() -> Unit)?,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onToggleIgnoreAssignments: ((Course) -> Unit)?,
    onDelete: (Course) -> Unit,
) {
    val style = LocalVisualStylePolicy.current
    val textColor = style.courseTextColor
    val markColor = style.courseSecondaryTextColor
    fun bgFor(course: Course) = style.courseFill(course.courseNo)
    fun outlineFor(course: Course) =
        if (outlineRequired && course.isRequired) style.courseOutline(course.courseNo) else null

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
            val labels = classTableCellLabels()
            fun cellLabel(course: Course, offset: Int, hasAssignment: Boolean, assignmentsIgnored: Boolean) =
                labels.label(
                    course,
                    weekday,
                    clusterPeriods.getOrNull(offset) ?: periodId,
                    hasAssignment,
                    assignmentsIgnored,
                    conflict = true,
                )

            val labelA = cellLabel(cellRole.courseA, cellRole.offsetA, hasAssignmentA, assignmentsIgnoredA)
            val labelB = cellLabel(cellRole.courseB, cellRole.offsetB, hasAssignmentB, assignmentsIgnoredB)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(aHeight)
                    .absoluteOffset(x = 0.dp, y = aTop)
                    .clip(shapeA)
                    .background(bgFor(cellRole.courseA))
                    .requiredCourseOutline(outlineFor(cellRole.courseA), shapeA)
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
                        // The app vibrates for this long press itself, through the Vibration setting;
                        // Compose's own buzz would land on top of it and ignore that setting.
                        hapticFeedbackEnabled = false,
                        onLongClick = onLongPress?.let { press -> { press(); showMenu = true } },
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
                CourseCellBadge(
                    hasAssignment = hasAssignmentA,
                    assignmentsIgnored = assignmentsIgnoredA,
                    tint = markColor,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(10.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bHeight)
                    .absoluteOffset(x = 0.dp, y = bTop)
                    .clip(shapeB)
                    .background(bgFor(cellRole.courseB))
                    .requiredCourseOutline(outlineFor(cellRole.courseB), shapeB)
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
                        // The app vibrates for this long press itself, through the Vibration setting;
                        // Compose's own buzz would land on top of it and ignore that setting.
                        hapticFeedbackEnabled = false,
                        onLongClick = onLongPress?.let { press -> { press(); showMenu = true } },
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
                CourseCellBadge(
                    hasAssignment = hasAssignmentB,
                    assignmentsIgnored = assignmentsIgnoredB,
                    tint = markColor,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .size(10.dp),
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                shape = RoundedCornerShape(12.dp),
            ) {
                listOf(
                    cellRole.courseA to assignmentsIgnoredA,
                    cellRole.courseB to assignmentsIgnoredB,
                ).forEachIndexed { idx, (course, ignored) ->
                    if (idx > 0) HorizontalDivider()
                    CourseActionSection(
                        course = course,
                        swatch = bgFor(course),
                        assignmentsIgnored = ignored,
                        onRename = { showMenu = false; onRename(course) },
                        onPickColor = { showMenu = false; onPickColor(course) },
                        onToggleIgnoreAssignments = onToggleIgnoreAssignments?.let { toggle ->
                            { showMenu = false; toggle(course) }
                        },
                        onDelete = { showMenu = false; onDelete(course) },
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
 * two columns instead of three. In the rows a course has alone it takes every
 * lane, drawn as one rounded outline with its seams to the courses beside it
 * kept ([ConflictLaneShape]), as the iPhone does (tigerduck-app#153) and as
 * the 2-course L-shape ([ConflictCourseCell]) takes the full width. Tapping a
 * course selects it directly (no picker), matching the home slider's per-band
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
    assignmentsIgnored: (String) -> Boolean,
    /** Whether a required course's lane is outlined; see [AppPreferences.outlineRequiredCourses]. */
    outlineRequired: Boolean = false,
    onSelect: (Course, String) -> Unit,
    /** Null on someone else's class table, which is read-only and has no menu. */
    onLongPress: (() -> Unit)?,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onToggleIgnoreAssignments: ((Course) -> Unit)?,
    onDelete: (Course) -> Unit,
) {
    val style = LocalVisualStylePolicy.current
    val textColor = style.courseTextColor
    val markColor = style.courseSecondaryTextColor
    fun bgFor(course: Course) = style.courseFill(course.courseNo)
    fun outlineFor(course: Course) =
        if (outlineRequired && course.isRequired) style.courseOutline(course.courseNo) else null
    val labels = classTableCellLabels()

    var menuForCourse by remember { mutableStateOf<Course?>(null) }

    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * cellRole.combinedSpan)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp),
    ) {
        // Lanes offset from the left in either direction, as the grid's blocks are.
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
            val rowHeight = maxHeight / cellRole.combinedSpan
            val laneWidth = maxWidth / cellRole.laneCount
            val indication = LocalIndication.current
            // A run within the cluster, inset by half the seam the lanes and rows keep between them.
            fun rectOf(run: LaneRun) = DpRect(
                left = laneWidth * run.lanes.first + LANE_SEAM / 2,
                top = rowHeight * run.firstRow + LANE_SEAM / 2,
                right = laneWidth * (run.lanes.last + 1) - LANE_SEAM / 2,
                bottom = rowHeight * (run.firstRow + run.rowCount) - LANE_SEAM / 2,
            )

            cellRole.members.forEach { member ->
                key(member.course.courseNo, member.offset) {
                    // Across every lane in the rows the course has alone (ClassTableCellLayout.laneRuns).
                    val runs = ClassTableCellLayout.laneRuns(member, cellRole)
                    val rects = runs.map(::rectOf)
                    val bounds = DpRect(
                        left = rects.minOf { it.left },
                        top = rects.minOf { it.top },
                        right = rects.maxOf { it.right },
                        bottom = rects.maxOf { it.bottom },
                    )
                    fun DpRect.inBounds() = DpRect(left - bounds.left, top - bounds.top, right - bounds.left, bottom - bounds.top)
                    val shape = remember(rects) { ConflictLaneShape(rects.map { it.inBounds() }, LANE_CORNER) }
                    // The name goes in the course's largest run, the mark in the corner of its last.
                    val labelRun = runs.indices.maxBy { runs[it].rowCount * (runs[it].lanes.last - runs[it].lanes.first + 1) }
                    val label = rects[labelRun].inBounds()
                    val last = rects.last().inBounds()
                    val hasAssignmentForMember = hasAssignment(member.course.courseNo)
                    val ignoredForMember = assignmentsIgnored(member.course.courseNo)
                    // Shared by the course's runs, so a press on any of them shows on its whole shape.
                    val interactionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = bounds.left, y = bounds.top)
                            .size(bounds.right - bounds.left, bounds.bottom - bounds.top)
                            .clip(shape)
                            .background(bgFor(member.course))
                            .requiredCourseOutline(outlineFor(member.course), shape)
                            .indication(interactionSource, indication),
                        // The name and the mark are placed by absolute offsets, from the left in
                        // either direction; TopStart would start them at the right edge in RTL.
                        contentAlignment = AbsoluteAlignment.TopLeft,
                    ) {
                        ClassTableCourseNameText(
                            text = member.course.displayName,
                            color = textColor,
                            maxLines = if (runs[labelRun].rowCount >= 2) 3 else 2,
                            modifier = Modifier
                                .absoluteOffset(x = label.left, y = label.top)
                                .size(label.right - label.left, label.bottom - label.top)
                                .padding(2.dp)
                                .wrapContentSize(Alignment.Center),
                        )
                        CourseCellBadge(
                            hasAssignment = hasAssignmentForMember,
                            assignmentsIgnored = ignoredForMember,
                            tint = markColor,
                            modifier = Modifier
                                .absoluteOffset(x = last.right - BADGE_INSET - BADGE_SIZE, y = last.bottom - BADGE_INSET - BADGE_SIZE)
                                .size(BADGE_SIZE),
                        )
                    }

                    // Taps land on the runs themselves: the shape's bounds reach over the courses
                    // beside it. The run holding the name is the course's one button for TalkBack
                    // and its one stop for a keyboard.
                    rects.forEachIndexed { index, rect ->
                        key(index) {
                            // A press shows on the whole shape, from where it was made on this run.
                            val runSource = remember { MutableInteractionSource() }
                            val fromShape = with(LocalDensity.current) {
                                Offset((rect.left - bounds.left).toPx(), (rect.top - bounds.top).toPx())
                            }
                            LaunchedEffect(runSource, fromShape) { forwardInteractions(runSource, interactionSource, fromShape) }
                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = rect.left, y = rect.top)
                                    .size(rect.right - rect.left, rect.bottom - rect.top)
                                    .then(
                                        if (index == labelRun) {
                                            Modifier.semantics(mergeDescendants = true) {
                                                contentDescription = labels.label(
                                                    member.course,
                                                    weekday,
                                                    member.firstPeriodId,
                                                    hasAssignmentForMember,
                                                    ignoredForMember,
                                                    conflict = true,
                                                )
                                                role = Role.Button
                                            }
                                        } else {
                                            Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false }
                                        },
                                    )
                                    .combinedClickable(
                                        interactionSource = runSource,
                                        indication = null,
                                        onClick = { onSelect(member.course, member.firstPeriodId) },
                                        // The app vibrates for this long press itself, through the Vibration setting;
                                        // Compose's own buzz would land on top of it and ignore that setting.
                                        hapticFeedbackEnabled = false,
                                        onLongClick = onLongPress?.let { press ->
                                            {
                                                press()
                                                menuForCourse = member.course
                                            }
                                        },
                                    ),
                            )
                        }
                    }
                }
            }

            menuForCourse?.let { course ->
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuForCourse = null },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    CourseActionSection(
                        course = course,
                        swatch = bgFor(course),
                        assignmentsIgnored = assignmentsIgnored(course.courseNo),
                        onRename = { menuForCourse = null; onRename(course) },
                        onPickColor = { menuForCourse = null; onPickColor(course) },
                        onToggleIgnoreAssignments = onToggleIgnoreAssignments?.let { toggle ->
                            { menuForCourse = null; toggle(course) }
                        },
                        onDelete = { menuForCourse = null; onDelete(course) },
                    )
                }
            }
        }
    }
}


/**
 * One course's actions, under a header that names it and shows its colour.
 *
 * The actions used to carry the name themselves -- "Rename — Calculus" --
 * which reads fine right up until the two courses in the conflict share a
 * name. Two sections of the same course colliding is an ordinary reason to
 * be in this menu, not an edge case, and there the old labels gave the user
 * two identical sets to choose between with nothing to tell them apart.
 *
 * The swatch is that something. It is [swatch], the colour the cell behind
 * the menu is already drawn in, so the choice is made by matching what is
 * on screen rather than by guessing which half of the cell was tapped.
 *
 * The header is deliberately not a [DropdownMenuItem]: it is a label, and
 * making it clickable would put a fourth, meaningless target in a menu
 * whose whole problem was ambiguity.
 */
@Composable
private fun CourseActionSection(
    course: Course,
    swatch: Color,
    assignmentsIgnored: Boolean,
    onRename: () -> Unit,
    onPickColor: () -> Unit,
    onToggleIgnoreAssignments: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(swatch),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            course.displayName,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
    }
    CourseEditMenuItems(
        assignmentsIgnored = assignmentsIgnored,
        onRename = onRename,
        onPickColor = onPickColor,
        onToggleIgnoreAssignments = onToggleIgnoreAssignments,
        onDelete = onDelete,
    )
}

/** The gap between two lanes, and between two rows, of a cluster of three or more. */
private val LANE_SEAM = 1.dp
private val LANE_CORNER = 4.dp
private val BADGE_SIZE = 10.dp
private val BADGE_INSET = 3.dp

/**
 * Passes [from]'s interactions on to [to], a press moved by [offset], with its release or cancel
 * ending the moved press: a run of a clashing course reports a press from its own corner, and the
 * course's whole shape, which draws it, starts it from its own. A press still held when this ends
 * (the run moved, which restarts it) is cancelled on [to], or the shape would stay pressed.
 */
internal suspend fun forwardInteractions(from: InteractionSource, to: MutableInteractionSource, offset: Offset) {
    val moved = mutableMapOf<PressInteraction.Press, PressInteraction.Press>()
    try {
        from.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> PressInteraction.Press(interaction.pressPosition + offset).let {
                    moved[interaction] = it
                    to.emit(it)
                }
                is PressInteraction.Release -> moved.remove(interaction.press)?.let { to.emit(PressInteraction.Release(it)) }
                is PressInteraction.Cancel -> moved.remove(interaction.press)?.let { to.emit(PressInteraction.Cancel(it)) }
                else -> to.emit(interaction)
            }
        }
    } finally {
        moved.values.forEach { to.tryEmit(PressInteraction.Cancel(it)) }
    }
}
