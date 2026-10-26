package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.component.WholeWordsText
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * Renames [course], prefilled with its current name. Shared by a cell's long-press menu and the
 * course detail's rename button, so both read the default from the same source.
 */
@Composable
internal fun CourseRenameDialog(
    viewModel: ClassTableViewModel,
    course: Course,
    onDismiss: () -> Unit,
) {
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val liveCourse = courses.find { it.courseNo == course.courseNo } ?: course
    val defaultName = remember(liveCourse) { viewModel.defaultNameFor(liveCourse) }
    var renameText by rememberSaveable(course.courseNo) {
        mutableStateOf(liveCourse.customCourseName ?: defaultName)
    }
    val trimmed = renameText.trim()
    val hasOverride = liveCourse.customCourseName != null
    // Revert is meaningful when a stored override exists OR the user has
    // typed away from the default; either way it restores the default.
    val canRevert = hasOverride || (trimmed.isNotEmpty() && trimmed != defaultName)
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.class_table_rename_title),
        confirmText = stringResource(R.string.action_confirm),
        onConfirm = {
            viewModel.setCustomCourseName(course.courseNo, renameText)
            onDismiss()
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.class_table_course_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                CourseRenameDefaultRow(
                    defaultName = defaultName,
                    canRevert = canRevert,
                    onRevert = {
                        if (hasOverride) {
                            viewModel.revertCourseName(course.courseNo)
                            onDismiss()
                        } else {
                            renameText = defaultName
                        }
                    },
                )
            }
        },
    )
}

/**
 * The default name, and the button that goes back to it. The name sits beside the button while
 * its longest word fits there; at the app's largest sizes the button leaves it too little room,
 * where it used to break "Default:" in the middle, so the name takes the whole width and the
 * button goes under it.
 */
@Composable
internal fun CourseRenameDefaultRow(defaultName: String, canRevert: Boolean, onRevert: () -> Unit) {
    BesideOrAbove(gap = 8.dp) {
        WholeWordsText(
            text = stringResource(R.string.class_table_rename_default_label, defaultName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onRevert,
            enabled = canRevert,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.class_table_rename_revert), maxLines = 1)
        }
    }
}

/**
 * Its two children, a text and the button that goes with it: side by side, the text taking
 * what the button leaves and both centred on one line, while the text's longest word fits in
 * that room; else the text across the whole width and the button under it, at the end.
 */
@Composable
private fun BesideOrAbove(gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val (text, button) = measurables
        val width = constraints.maxWidth
        val buttonPlaceable = button.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gapPx = gap.roundToPx()
        val room = width - buttonPlaceable.width - gapPx
        // Unbounded, as an intrinsic measurement asks: the row as wide as it needs.
        val beside = !constraints.hasBoundedWidth || text.minIntrinsicWidth(Constraints.Infinity) <= room
        if (beside) {
            val textPlaceable = text.measure(
                Constraints(maxWidth = if (constraints.hasBoundedWidth) room.coerceAtLeast(0) else Constraints.Infinity),
            )
            val height = maxOf(textPlaceable.height, buttonPlaceable.height)
            val rowWidth = if (constraints.hasBoundedWidth) width else textPlaceable.width + gapPx + buttonPlaceable.width
            layout(rowWidth, height) {
                textPlaceable.placeRelative(0, (height - textPlaceable.height) / 2)
                buttonPlaceable.placeRelative(rowWidth - buttonPlaceable.width, (height - buttonPlaceable.height) / 2)
            }
        } else {
            val textPlaceable = text.measure(Constraints(minWidth = width, maxWidth = width))
            layout(width, textPlaceable.height + buttonPlaceable.height) {
                textPlaceable.placeRelative(0, 0)
                buttonPlaceable.placeRelative(width - buttonPlaceable.width, textPlaceable.height)
            }
        }
    }
}
