package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.ui.component.NO_DUE_DATE
import org.ntust.app.tigerduck.ui.component.SkippedByTalkBack
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.text.SimpleDateFormat
import java.util.Locale

/** An outstanding assignment: its title and due time, a dash TalkBack skips without one, opening its page. */
@Composable
internal fun CourseDetailAssignmentRow(assignment: Assignment, onClick: () -> Unit) {
    val dueDate = assignment.studentDueDate
    val due = remember(dueDate) {
        dueDate?.let { SimpleDateFormat("M/d HH:mm", Locale.getDefault()).apply { timeZone = AppConstants.TAIPEI_TZ }.format(it) }
    }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(assignment.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            SkippedByTalkBack(hidden = due == null) {
                Text(
                    text = stringResource(R.string.course_detail_due_prefix, due ?: NO_DUE_DATE),
                    color = if (assignment.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            )
        },
    )
}
