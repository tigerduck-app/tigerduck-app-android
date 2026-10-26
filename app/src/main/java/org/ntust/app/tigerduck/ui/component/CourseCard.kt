package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/**
 * Card widths, named because the today's-courses carousel scrolls itself to
 * the ongoing class and has to know how wide everything before it is. A
 * literal in each place would drift the moment one card was resized.
 */
val CourseCardWidth = 160.dp
val CurrentClassCardWidth = 220.dp

/** Gap between cards in the carousel. */
val CourseCardGap = 12.dp

@Composable
fun CourseCard(
    course: Course,
    timeRange: String? = null,
    hasAssignment: Boolean = false,
    isFinished: Boolean = false,
    weekday: Int? = null,
    /** The block of [weekday] the card stands for; its room is the one shown. */
    periods: List<String>? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = LocalVisualStylePolicy.current
    val isDark = TigerDuckTheme.isDarkMode
    val cardColor = style.courseCardFill(
        // Vibrant (light-palette) color so the tint stays visible in dark mode.
        courseColor = TigerDuckTheme.courseColorVibrant(course.courseNo),
        tintAlpha = when {
            isFinished -> 0.35f
            isDark -> 0.55f
            else -> 0.50f
        },
        muted = isFinished,
        surface = MaterialTheme.colorScheme.surface,
        isDark = isDark,
    )
    // A class already over fades its text as well as its fill.
    val textAlpha = if (isFinished) 0.4f else 1f
    val textColor = style.courseCardTextColor(MaterialTheme.colorScheme.onSurface).fadedBy(textAlpha)
    val secondaryColor =
        style.courseCardSecondaryTextColor(MaterialTheme.colorScheme.onSurface).fadedBy(textAlpha)

    Card(
        onClick = onClick,
        modifier = modifier
            .width(CourseCardWidth),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            // The press ripple and anything left in the default colour read on the fill.
            contentColor = style.courseCardTextColor(LocalContentColor.current),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                val nameStyle = MaterialTheme.typography.bodyMedium
                    .copy(fontWeight = FontWeight.SemiBold)
                Text(
                    text = courseNameForDisplay(course.displayName, maxChars = 28),
                    style = nameStyle.copy(
                        fontSize = nameStyle.fontSize * TigerDuckTheme.courseNameScale
                    ),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val room = cardRoom(course, weekday, periods)
                if (room.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = room,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                if (timeRange != null) {
                    Text(
                        text = timeRange,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (hasAssignment) {
                Icon(
                    imageVector = Icons.Filled.Book,
                    contentDescription = stringResource(R.string.course_card_assignment_content_description),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(14.dp),
                    tint = style.courseCardMarkColor.fadedBy(textAlpha)
                )
            }
        }
    }
}

@Composable
fun CurrentClassCard(
    course: Course,
    blockStartMinute: Int,
    blockEndMinute: Int,
    currentMinute: Int,
    hasAssignment: Boolean = false,
    weekday: Int? = null,
    /** The periods of the block going on; its room is the one shown. */
    periods: List<String>? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = LocalVisualStylePolicy.current
    val cardColor = style.courseCardFill(
        courseColor = TigerDuckTheme.courseColorVibrant(course.courseNo),
        tintAlpha = 0.70f,
        muted = false,
        surface = MaterialTheme.colorScheme.surface,
        isDark = TigerDuckTheme.isDarkMode,
    )
    val textColor = style.courseCardTextColor(MaterialTheme.colorScheme.onSurface)
    val secondaryColor = style.courseCardSecondaryTextColor(MaterialTheme.colorScheme.onSurface)
    val progress = if (blockEndMinute > blockStartMinute) {
        ((currentMinute - blockStartMinute).toFloat() /
                (blockEndMinute - blockStartMinute)).coerceIn(0f, 1f)
    } else 0f
    val timeRange = "${formatHm(blockStartMinute)} - ${formatHm(blockEndMinute)}"

    Card(
        onClick = onClick,
        modifier = modifier.width(CurrentClassCardWidth),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = style.courseCardTextColor(LocalContentColor.current),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B30))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.course_card_current_class),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = courseNameForDisplay(course.displayName, maxChars = 28),
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val room = cardRoom(course, weekday, periods)
                if (room.isNotEmpty()) {
                    Text(
                        text = room,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = textColor,
                    trackColor = textColor.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = timeRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor
                )
            }

            if (hasAssignment) {
                Icon(
                    imageVector = Icons.Filled.Book,
                    contentDescription = stringResource(R.string.course_card_assignment_content_description),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(14.dp),
                    tint = style.courseCardMarkColor
                )
            }
        }
    }
}

/** This colour with its alpha scaled by [share]. */
private fun Color.fadedBy(share: Float): Color = copy(alpha = alpha * share)

private fun formatHm(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/**
 * The room a course card shows: its block's when the card stands for one, so a course meeting in
 * two rooms that day shows the one it is in; else the day's rooms, or the week's.
 */
internal fun cardRoom(course: Course, weekday: Int?, periods: List<String>?): String = when {
    weekday == null -> Course.dedupRooms(course.classroom)
    periods.isNullOrEmpty() -> course.classroom(weekday)
    else -> course.blockClassroom(weekday, periods)
}
