// The ordinary single-course cell, and the course-name text that has to
// fit inside it. 衝堂 (two or more courses in one slot) is a different
// enough layout problem to live in ClassTableConflictCells.kt.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.isEnglishUiLanguage
import org.ntust.app.tigerduck.ui.component.middleEllipsize
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
    hasAssignment: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onDelete: (Course) -> Unit,
) {
    val cellBg = if (TigerDuckTheme.isDarkMode) {
        TigerDuckTheme.courseColor(course.courseNo)
    } else {
        TigerDuckTheme.courseColorVibrant(course.courseNo).copy(alpha = 0.50f)
    }
    val cellTextColor = if (TigerDuckTheme.isDarkMode) Color.White else Color(0xFF1C1C1E)
    var showMenu by remember { mutableStateOf(false) }
    val assignmentLabel = stringResource(R.string.a11y_class_table_cell_assignment_indicator)
    val cellRoom = course.classroom(weekday)
    val cellLabel = buildString {
        append(course.displayName)
        if (cellRoom.isNotBlank()) {
            append(", ")
            append(cellRoom)
        }
        if (hasAssignment) {
            append(". ")
            append(assignmentLabel)
        }
    }
    // A notch under the course name, so the name keeps the visual weight:
    // the room is a reminder, not a second title.
    val hintFontSize = 10.sp * TigerDuckTheme.courseNameScale * 0.85f
    val hintLineHeight = hintFontSize * 1.2f
    val hintSpace = if (roomHint == null) {
        0.dp
    } else {
        with(LocalDensity.current) { hintLineHeight.toDp() } + 2.dp
    }
    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * spanCount)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(cellBg)
            .semantics(mergeDescendants = true) {
                contentDescription = cellLabel
                role = Role.Button
            }
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    onLongPress()
                    showMenu = true
                },
            ),
    ) {
        ClassTableCourseNameText(
            text = course.displayName,
            color = cellTextColor,
            maxLines = if (spanCount >= 2) 3 else 2,
            modifier = Modifier
                .padding(2.dp)
                // Hand the hint's line back to it. Without this the centred
                // name keeps the whole cell, and a two-line name in a
                // one-period cell prints straight through the room.
                .padding(bottom = hintSpace)
                .align(Alignment.Center),
        )
        if (roomHint != null) {
            Text(
                text = roomHint,
                style = MaterialTheme.typography.labelSmall,
                color = cellTextColor.copy(alpha = 0.7f),
                fontSize = hintFontSize,
                lineHeight = hintLineHeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Clear of the assignment badge in the opposite corner.
                    .padding(start = 3.dp, bottom = 2.dp, end = if (hasAssignment) 18.dp else 3.dp)
                    // Already in the cell's own label, which reads the room.
                    .clearAndSetSemantics {},
            )
        }
        if (hasAssignment) {
            Icon(
                imageVector = Icons.Filled.Book,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(12.dp),
                tint = cellTextColor.copy(alpha = 0.7f),
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(12.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.class_table_rename_title)) },
                onClick = { showMenu = false; onRename(course) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.class_table_pick_color)) },
                onClick = { showMenu = false; onPickColor(course) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.class_table_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { showMenu = false; onDelete(course) },
            )
        }
    }
}

@Composable
internal fun ClassTableCourseNameText(
    text: String,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val useMiddle = isEnglishUiLanguage()
    var displayText by remember(text, useMiddle) { mutableStateOf(text) }
    Text(
        text = displayText,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
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
