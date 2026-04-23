package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentStatus
import org.ntust.app.tigerduck.data.model.status
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// iOS-matched semantic colors so home status reads the same on both
// platforms regardless of Material theme tint.
private val BadgeGreen = Color(0xFF34C759)
private val BadgeOrange = Color(0xFFFF9500)
private val BadgeRed = Color(0xFFFF3B30)

@Composable
fun AssignmentItem(
    assignment: Assignment,
    modifier: Modifier = Modifier,
    showAbsoluteTime: Boolean = false,
    markedCompleted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val courseColor = TigerDuckTheme.courseColorVibrant(assignment.courseNo)
    val status = remember(
        assignment.assignmentId,
        assignment.isCompleted,
        assignment.dueDate,
        assignment.cutoffDate,
        assignment.submittedAt,
    ) { assignment.status() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { base -> if (onClick != null) base.clickable { onClick() } else base }
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = assignment.courseName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }

        Spacer(Modifier.width(8.dp))

        AssignmentTrailing(
            assignment = assignment,
            status = status,
            showAbsoluteTime = showAbsoluteTime,
            markedCompleted = markedCompleted,
        )
    }
}

@Composable
private fun AssignmentTrailing(
    assignment: Assignment,
    status: AssignmentStatus,
    showAbsoluteTime: Boolean,
    markedCompleted: Boolean,
) {
    // Show the Moodle-derived badge AND the manual "標示為完成" tag side-
    // by-side when both apply (e.g. an overdue assignment the user manually
    // marked done renders as "逾期 標示為完成"). The two badges are
    // independent signals — one is from Moodle, the other from the user.
    val moodleBadge = statusBadge(status)
    val markedBadge: Pair<String, Color>? = if (markedCompleted) "標示為完成" to BadgeGreen else null
    val isOverdue = status == AssignmentStatus.OVERDUE_ACCEPTABLE ||
            status == AssignmentStatus.OVERDUE_REJECTED
    val emphasise = status == AssignmentStatus.OVERDUE_REJECTED

    Column(horizontalAlignment = Alignment.End) {
        val badges = listOfNotNull(moodleBadge, markedBadge)
        if (badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                badges.forEach { (label, color) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (emphasise && label != "標示為完成") FontWeight.Bold
                                         else FontWeight.SemiBold,
                        ),
                        color = color,
                    )
                }
            }
        }

        val now = remember { Date() }
        val useAbsolute = showAbsoluteTime ||
                ((assignment.isCompleted || markedCompleted) && assignment.dueDate.before(now))
        val timeText = if (useAbsolute) formatAbsolute(assignment.dueDate)
                       else formatRelative(assignment.dueDate, now)
        val timeColor = if (isOverdue) BadgeRed
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        val timeWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = timeWeight),
            color = timeColor,
        )
    }
}

/** iOS `AssignmentStatus.badgeLabel` + `.tint`, ported verbatim. */
private fun statusBadge(status: AssignmentStatus): Pair<String, Color>? = when (status) {
    AssignmentStatus.PENDING -> null
    AssignmentStatus.SUBMITTED -> "已繳交" to BadgeGreen
    AssignmentStatus.SUBMITTED_LATE -> "已遲交" to BadgeOrange
    AssignmentStatus.OVERDUE_ACCEPTABLE -> "逾期" to BadgeRed
    AssignmentStatus.OVERDUE_REJECTED -> "逾期拒收" to BadgeRed
}

private fun formatAbsolute(date: Date): String =
    SimpleDateFormat("M/d HH:mm:ss", Locale.getDefault()).format(date)

/**
 * Port of iOS `Date.relativeTimeString(from:)`. Steps units naturally so the
 * reader can see how overdue (or how soon) something is at a glance, instead
 * of a flat "已逾期" / "已過期" string.
 */
private fun formatRelative(date: Date, now: Date): String {
    val diffMs = date.time - now.time
    val isPast = diffMs < 0
    val suffix = if (isPast) "前" else "後"
    val absMs = abs(diffMs)
    val days = TimeUnit.MILLISECONDS.toDays(absMs).toInt()
    if (days > 3) return "$days 天$suffix"
    val hours = TimeUnit.MILLISECONDS.toHours(absMs).toInt()
    if (hours > 0) return "$hours 小時$suffix"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(absMs).toInt()
    if (minutes > 0) return "$minutes 分鐘$suffix"
    val seconds = TimeUnit.MILLISECONDS.toSeconds(absMs).toInt()
    return "$seconds 秒鐘$suffix"
}
