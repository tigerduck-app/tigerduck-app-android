package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentStatus
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.model.status
import org.ntust.app.tigerduck.shared.clock.AppClock
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
    course: Course? = null,
    showAbsoluteTime: Boolean = false,
    markedCompleted: Boolean = false,
    isIgnored: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val courseColor = TigerDuckTheme.courseColorVibrant(assignment.courseNo)
    // Subscribe to AppClock changes so toggling the debug clock (forward,
    // back, or off) re-evaluates status — otherwise the badge would stay
    // pinned to whatever "now" was the first time this row composed.
    val clockVersion by rememberAppClockVersion()
    val status = remember(
        assignment.assignmentId,
        assignment.isCompleted,
        assignment.dueDate,
        assignment.cutoffDate,
        assignment.submittedAt,
        clockVersion,
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
                text = courseLineLabel(assignment, course),
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
            isIgnored = isIgnored,
            clockVersion = clockVersion,
        )
    }
}

@Composable
private fun AssignmentTrailing(
    assignment: Assignment,
    status: AssignmentStatus,
    showAbsoluteTime: Boolean,
    markedCompleted: Boolean,
    isIgnored: Boolean,
    clockVersion: Long,
) {
    val moodleBadge = statusBadge(status)
    val markCompleteLabel = stringResource(R.string.assignment_mark_complete)
    val ignoredLabel = stringResource(R.string.assignment_filter_ignored)
    val overdueLabel = stringResource(R.string.assignment_status_overdue)
    val overdueRejectedLabel = stringResource(R.string.assignment_status_overdue_rejected)
    val markedBadge: Pair<String, Color>? =
        if (markedCompleted) markCompleteLabel to BadgeGreen else null
    val ignoredBadge: Pair<String, Color>? =
        if (isIgnored) ignoredLabel to MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY) else null
    val isOverdue = status == AssignmentStatus.OVERDUE_ACCEPTABLE ||
            status == AssignmentStatus.OVERDUE_REJECTED
    val emphasise = status == AssignmentStatus.OVERDUE_REJECTED

    val now = remember(clockVersion) { Date(AppClock.nowMillis()) }

    // Secondary overdue badge: when an item is locally completed or ignored
    // but past due, show the overdue badge stacked above (matches iOS
    // secondaryBadge). Also shown for submitted + locally completed.
    val secondaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    val secondaryBadge: Pair<String, Color>? = when {
        (status == AssignmentStatus.SUBMITTED || status == AssignmentStatus.SUBMITTED_LATE) && markedCompleted ->
            markCompleteLabel to BadgeGreen
        (status == AssignmentStatus.SUBMITTED || status == AssignmentStatus.SUBMITTED_LATE) && isIgnored ->
            ignoredLabel to secondaryColor
        (markedCompleted || isIgnored) && assignment.dueDate.before(now) -> {
            if (assignment.cutoffDate != null && now.after(assignment.cutoffDate))
                overdueRejectedLabel to BadgeRed
            else overdueLabel to BadgeRed
        }
        else -> null
    }

    Column(horizontalAlignment = Alignment.End) {
        val badges = listOfNotNull(secondaryBadge, moodleBadge, ignoredBadge, markedBadge)
        if (badges.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End) {
                badges.forEach { (label, color) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (emphasise && label != markCompleteLabel && label != ignoredLabel) FontWeight.Bold
                            else FontWeight.SemiBold,
                        ),
                        color = color,
                    )
                }
            }
        }

        val useAbsolute = showAbsoluteTime ||
                ((assignment.isCompleted || markedCompleted || isIgnored) && assignment.dueDate.before(now))
        val timeText = if (useAbsolute) formatAbsolute(assignment.dueDate)
        else formatRelative(assignment.dueDate, now)
        val timeColor = when {
            isOverdue -> BadgeRed
            (isIgnored || markedCompleted) && assignment.dueDate.before(now) -> BadgeRed
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        }
        val timeWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = timeWeight),
            color = timeColor,
        )
    }
}

/**
 * "Course name • Course no." line shown beneath each assignment title.
 * Mirrors iOS `SDAssignment.courseLineLabel(matching:)`: prefers the in-memory
 * [Course] so user renames and the canonical NTUST code win over Moodle's
 * fullname, then falls back to the cached [Assignment.courseName] when the
 * roster hasn't loaded. Drops the code when it's missing or already equal to
 * the name (unknown courses whose name falls back to the courseNo).
 */
private fun courseLineLabel(assignment: Assignment, course: Course?): String {
    val matched = course?.takeIf { it.courseNo == assignment.courseNo }
    val name = matched?.displayName ?: assignment.courseName
    val code = matched?.courseNo ?: assignment.courseNo
    if (code.isEmpty() || name.isEmpty() || name == code) return name
    return "$name • $code"
}

/** iOS `AssignmentStatus.badgeLabel` + `.tint`, ported verbatim. */
@Composable
private fun statusBadge(status: AssignmentStatus): Pair<String, Color>? = when (status) {
    AssignmentStatus.PENDING -> null
    AssignmentStatus.SUBMITTED -> stringResource(R.string.assignment_status_submitted) to BadgeGreen
    AssignmentStatus.SUBMITTED_LATE -> stringResource(R.string.assignment_status_submitted_late) to BadgeOrange
    AssignmentStatus.OVERDUE_ACCEPTABLE -> stringResource(R.string.assignment_status_overdue) to BadgeRed
    AssignmentStatus.OVERDUE_REJECTED -> stringResource(R.string.assignment_status_overdue_rejected) to BadgeRed
}

/**
 * Bridges [AppClock]'s listener mechanism into Compose. The returned state
 * advances each time [AppClock.setOverride] is called AND once per minute as
 * wall-clock time progresses, so any composable that keys on it recomputes
 * against a fresh "now". Without the periodic pulse a row that composed at
 * "in 5 minutes" would stay frozen at that label until something else
 * triggered a recomposition.
 */
@Composable
private fun rememberAppClockVersion(): State<Long> =
    produceState(initialValue = AppClock.version()) {
        val listener: (Long) -> Unit = { value = value + 1 }
        AppClock.addOverrideListener(listener)
        try {
            // Refresh once in case setOverride fired between initialValue
            // capture and listener registration.
            value = value + 1
            while (true) {
                kotlinx.coroutines.delay(60_000)
                value = value + 1
            }
        } finally {
            AppClock.removeOverrideListener(listener)
        }
    }

private fun formatAbsolute(date: Date): String =
    SimpleDateFormat("M/d HH:mm:ss", Locale.getDefault()).format(date)

/**
 * Port of iOS `Date.relativeTimeString(from:)`. Steps units naturally so the
 * reader can see how overdue (or how soon) something is at a glance, instead
 * of a flat overdue string.
 */
@Composable
private fun formatRelative(date: Date, now: Date): String {
    val diffMs = date.time - now.time
    val isPast = diffMs < 0
    val suffix = if (isPast) stringResource(R.string.assignment_time_suffix_ago)
    else stringResource(R.string.assignment_time_suffix_later)
    val absMs = abs(diffMs)
    val days = TimeUnit.MILLISECONDS.toDays(absMs).toInt()
    if (days > 3) return stringResource(R.string.assignment_time_days_with_suffix, days, suffix)
    val hours = TimeUnit.MILLISECONDS.toHours(absMs).toInt()
    if (hours > 0) return stringResource(R.string.assignment_time_hours_with_suffix, hours, suffix)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(absMs).toInt()
    if (minutes > 0) return stringResource(
        R.string.assignment_time_minutes_with_suffix,
        minutes,
        suffix
    )
    val seconds = TimeUnit.MILLISECONDS.toSeconds(absMs).toInt()
    return stringResource(R.string.assignment_time_seconds_with_suffix, seconds, suffix)
}
