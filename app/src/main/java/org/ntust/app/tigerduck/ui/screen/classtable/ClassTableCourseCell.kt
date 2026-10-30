// The ordinary single-course cell, and the course-name text that has to
// fit inside it. 衝堂 (two or more courses in one slot) is a different
// enough layout problem to live in ClassTableConflictCells.kt.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.courseChangeInk
import org.ntust.app.tigerduck.ui.component.isEnglishUiLanguage
import org.ntust.app.tigerduck.ui.component.middleEllipsize
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoloCourseCell(
    course: Course,
    /** The room to print in the bottom-left corner, or null for none. */
    roomHint: String?,
    spanCount: Int,
    dayColWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    weekday: Int,
    /**
     * The period the tile starts at, which its TalkBack label names. Null reads the label
     * without it, as before tiles named their period.
     */
    periodId: String? = null,
    hasAssignment: Boolean,
    assignmentsIgnored: Boolean,
    /** Whether a required course's cell is outlined; see [AppPreferences.outlineRequiredCourses]. */
    outlineRequired: Boolean = false,
    onTap: () -> Unit,
    /** Null on someone else's class table, which is read-only and has no menu. */
    onLongPress: (() -> Unit)?,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    /** Null where the menu does not offer it; see [ClassTableViewModel.canIgnoreAssignments]. */
    onToggleIgnoreAssignments: ((Course) -> Unit)?,
    onDelete: (Course) -> Unit,
    /** The block as the school changed it on its column's date; null for the timetable's. */
    change: ClassSlot? = null,
) {
    val style = LocalVisualStylePolicy.current
    val tiles = style.drawsPeriodTiles
    val cancelled = change?.isCancelled == true
    // A cancelled class stays in place, faded and struck through, as on iOS.
    val cellBg = style.courseFill(course.courseNo).let { if (cancelled) it.copy(alpha = it.alpha * 0.4f) else it }
    val cellTextColor = style.courseTextColor.let { if (cancelled) it.copy(alpha = 0.45f) else it }
    val nameDecoration = if (cancelled) TextDecoration.LineThrough else null
    val cellSecondaryColor = style.courseSecondaryTextColor
    val ink = courseChangeInk()
    val outline = if (outlineRequired && course.isRequired) style.courseOutline(course.courseNo) else null
    val blockShape = RoundedCornerShape(if (tiles) PERIOD_TILE_CORNER else 6.dp)
    var showMenu by remember { mutableStateOf(false) }
    val cancelledLabel = stringResource(R.string.course_card_cancelled)
    val locale = LocalConfiguration.current.locales[0]
    val cellLabel = classTableCellLabels()
        .label(course, weekday, periodId, hasAssignment, assignmentsIgnored)
        .let { label -> change?.let { label + spokenChanges(it, cancelledLabel, locale) } ?: label }
    // The bottom line: the cancellation whether or not rooms show, else a moved room where they do.
    val bottomLine: String? = when {
        cancelled -> cancelledLabel
        change?.hasChangedRoom == true && roomHint != null -> change.room
        else -> roomHint
    }
    val timeLine = change?.takeIf { !cancelled }?.let(::changedTimeText)
    // A notch under the course name, so the name keeps the visual weight:
    // the room is a reminder, not a second title.
    val hintFontSize = 10.sp * TigerDuckTheme.courseNameScale * 0.85f
    val hintLineHeight = hintFontSize * 1.2f
    val lineSpace = with(LocalDensity.current) { hintLineHeight.toDp() } + 2.dp
    val hintSpace = if (bottomLine == null) 0.dp else lineSpace
    val topSpace = if (timeLine == null) 0.dp else lineSpace
    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * spanCount)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp)
            .clip(blockShape)
            // Period tiles fill themselves, leaving the gaps between them bare,
            // and each draws its own outline.
            .then(if (tiles) Modifier else Modifier.background(cellBg).requiredCourseOutline(outline, blockShape))
            .combinedClickable(
                onClick = onTap,
                // The app vibrates for this long press itself, through the Vibration setting;
                // Compose's own buzz would land on top of it and ignore that setting.
                hapticFeedbackEnabled = false,
                onLongClick = onLongPress?.let { press ->
                    {
                        press()
                        showMenu = true
                    }
                },
            )
            // The label stands for the name, room hint and badge drawn inside.
            // Cleared here, after the clickable, rather than on a child: a child
            // that clears its own semantics leaves this node unclickable to
            // TalkBack, and a clear before the clickable would drop its actions.
            .clearAndSetSemantics {
                contentDescription = cellLabel
                role = Role.Button
            },
    ) {
        if (tiles) {
            ClassTablePeriodTiles(
                name = course.displayName,
                spanCount = spanCount,
                cellHeight = cellHeight,
                fill = cellBg,
                textColor = cellTextColor,
                nameDecoration = nameDecoration,
                // A tile's third line reaches the bottom corners, so the last
                // tile also leaves its line to the mark, not only to the hint.
                lastTileBottomSpace = if (hasAssignment || assignmentsIgnored) {
                    maxOf(hintSpace, CORNER_MARK_SPACE)
                } else {
                    hintSpace
                },
                outline = outline,
            )
        } else {
            ClassTableCourseNameText(
                text = course.displayName,
                color = cellTextColor,
                textDecoration = nameDecoration,
                maxLines = if (spanCount >= 2) 3 else 2,
                modifier = Modifier
                    .padding(2.dp)
                    // Hand the hint's line back to it. Without this the centred
                    // name keeps the whole cell, and a two-line name in a
                    // one-period cell prints straight through the room.
                    .padding(bottom = hintSpace, top = topSpace)
                    .align(Alignment.Center),
            )
        }
        if (bottomLine != null) {
            val moved = !cancelled && bottomLine != roomHint
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Clear of the assignment badge in the opposite corner.
                    .padding(
                        start = 3.dp,
                        bottom = 2.dp,
                        end = if (hasAssignment || assignmentsIgnored) 18.dp else 3.dp,
                    ),
            ) {
                if (moved) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(with(LocalDensity.current) { hintLineHeight.toDp() }),
                    )
                }
                Text(
                    text = bottomLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (moved) ink else cellSecondaryColor,
                    fontWeight = if (cancelled || moved) FontWeight.SemiBold else null,
                    fontSize = hintFontSize,
                    lineHeight = hintLineHeight,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (timeLine != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 3.dp, top = 2.dp, end = 3.dp),
            ) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(with(LocalDensity.current) { hintLineHeight.toDp() }),
                )
                Text(
                    text = timeLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = hintFontSize,
                    lineHeight = hintLineHeight,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (change?.notes?.isNotEmpty() == true) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = ink,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(9.dp),
            )
        }
        CourseCellBadge(
            hasAssignment = hasAssignment,
            assignmentsIgnored = assignmentsIgnored,
            tint = cellSecondaryColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .size(12.dp),
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(12.dp),
        ) {
            CourseEditMenuItems(
                assignmentsIgnored = assignmentsIgnored,
                onRename = { showMenu = false; onRename(course) },
                onPickColor = { showMenu = false; onPickColor(course) },
                onToggleIgnoreAssignments = onToggleIgnoreAssignments?.let { toggle ->
                    { showMenu = false; toggle(course) }
                },
                onDelete = { showMenu = false; onDelete(course) },
            )
        }
    }
}

/** A changed block's time as the block shows it: both ends, the start alone, or the end alone. */
internal fun changedTimeText(meeting: ClassSlot): String? = when {
    meeting.originalStart != null && meeting.originalEnd != null -> "${hm(meeting.start)}–${hm(meeting.end)}"
    meeting.originalStart != null -> hm(meeting.start)
    meeting.originalEnd != null -> "–${hm(meeting.end)}"
    else -> null
}

/** What TalkBack adds for a changed block: the cancellation or the new room and time, then the notes. */
internal fun spokenChanges(meeting: ClassSlot, cancelled: String, locale: java.util.Locale): String {
    val parts = if (meeting.isCancelled) {
        listOf(cancelled)
    } else {
        listOfNotNull(
            meeting.room.takeIf { meeting.hasChangedRoom },
            "${hm(meeting.start)} - ${hm(meeting.end)}".takeIf { meeting.hasChangedTime },
        )
    }
    return (parts + meeting.notes.mapNotNull { it.note(locale) }).joinToString("") { ". $it" }
}

private fun hm(time: java.time.LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/**
 * What a period tile's name leaves below it for the corner mark: the mark's
 * 12dp and 3dp inset, less the 2dp the name already keeps around itself. Any
 * more and a two-line name no longer fits a one-period tile.
 */
private val CORNER_MARK_SPACE = 13.dp

/**
 * A course's long-press actions, in the order the iPhone's menu has them, each
 * with the glyph that makes the list quicker to scan than its words alone;
 * Delete in the error colour, glyph and all, as the one that cannot be undone.
 * Shared by the single-course cell and the per-course sections of a 衝堂
 * cell's menu, so the two menus cannot drift apart.
 */
@Composable
internal fun CourseEditMenuItems(
    assignmentsIgnored: Boolean,
    onRename: () -> Unit,
    onPickColor: () -> Unit,
    /** Null where the menu does not offer it; see [ClassTableViewModel.canIgnoreAssignments]. */
    onToggleIgnoreAssignments: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_rename_title)) },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        onClick = onRename,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_pick_color)) },
        leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
        onClick = onPickColor,
    )
    if (onToggleIgnoreAssignments != null) {
        IgnoreAssignmentsMenuItem(ignored = assignmentsIgnored, onClick = onToggleIgnoreAssignments)
    }
    DropdownMenuItem(
        text = {
            Text(
                stringResource(R.string.class_table_delete),
                color = MaterialTheme.colorScheme.error,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        onClick = onDelete,
    )
}

/** The long-press action that ignores a course's assignments, or stops ignoring them. */
@Composable
internal fun IgnoreAssignmentsMenuItem(ignored: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(
                    if (ignored) {
                        R.string.class_table_ignore_assignments_undo
                    } else {
                        R.string.class_table_ignore_assignments
                    }
                )
            )
        },
        // The eye says what the action does to the course's marks: closed to
        // start ignoring them, open to see them again.
        leadingIcon = {
            Icon(
                if (ignored) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = null,
            )
        },
        onClick = onClick,
    )
}

@Composable
internal fun ClassTableCourseNameText(
    text: String,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
    /** A line through the name, as for a cancelled class. */
    textDecoration: TextDecoration? = null,
) {
    val useMiddle = isEnglishUiLanguage()
    var displayText by remember(text, useMiddle) { mutableStateOf(text) }
    Text(
        text = displayText,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
        textDecoration = textDecoration,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        fontSize = 10.sp * TigerDuckTheme.courseNameScale,
        onTextLayout = { layout ->
            if (!useMiddle) {
                if (displayText != text) displayText = text
                return@Text
            }
            if (!layout.hasVisualOverflow || layout.lineCount == 0) return@Text
            val capacity = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
            val next = middleEllipsize(text, capacity.coerceAtLeast(5))
            if (next != displayText) displayText = next
        }
    )
}

/**
 * Course-detail popup styled after the iOS app: large title and a slim
 * course-colored bar at the top, then two emphasized cards displaying the
 * fields users glance at most (Classroom and Time), followed by the
 * remaining metadata as label/value rows and the outstanding-assignments
 * list. Rendered as a custom [Dialog] wrapping a Material 3 [Surface] so
 * the layout can flex beyond what [AlertDialog]'s title/text/buttons slots
 * allow, while keeping the same shape, container color, and tonal
 * elevation as other dialogs in the app (see [LoginSheet]).
 */
