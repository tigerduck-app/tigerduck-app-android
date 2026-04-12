package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun AssignmentItem(
    assignment: Assignment,
    modifier: Modifier = Modifier,
    showAbsoluteTime: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val courseColor = TigerDuckTheme.courseColorVibrant(assignment.courseNo)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { base ->
                if (onClick != null) base.clickable { onClick() } else base
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .background(courseColor)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = assignment.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (assignment.isOverdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = assignment.courseName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = if (showAbsoluteTime) formatAbsolute(assignment.dueDate)
                   else formatRelative(assignment.dueDate),
            style = MaterialTheme.typography.labelSmall,
            color = if (assignment.isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}

private fun formatAbsolute(date: Date): String {
    val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return fmt.format(date)
}

private fun formatRelative(date: Date): String {
    val diff = date.time - System.currentTimeMillis()
    return when {
        diff < 0 -> "已過期"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} 分鐘後"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} 小時後"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff)} 天後"
    }
}
