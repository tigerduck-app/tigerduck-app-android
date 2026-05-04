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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

@Composable
fun CourseCard(
    course: Course,
    timeRange: String? = null,
    hasAssignment: Boolean = false,
    isFinished: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Vibrant (light-palette) color so the tint stays visible in dark mode.
    val color = TigerDuckTheme.courseColorVibrant(course.courseNo)
    val surface = MaterialTheme.colorScheme.surface
    val lightAlpha = if (isFinished) 0.35f else 0.50f
    val darkAlpha = if (isFinished) 0.35f else 0.55f
    val cardColor = if (TigerDuckTheme.isDarkMode) {
        color.copy(alpha = darkAlpha).compositeOver(surface)
    } else {
        color.copy(alpha = lightAlpha)
    }
    val textAlpha = if (isFinished) 0.4f else 1f

    Card(
        onClick = onClick,
        modifier = modifier
            .width(160.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    text = courseNameForDisplay(course.courseName, maxChars = 28),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (course.classroom.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = course.classroom,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY * textAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                if (timeRange != null) {
                    Text(
                        text = timeRange,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY * textAlpha),
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
                    tint = Color.Gray.copy(alpha = 0.5f * textAlpha)
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = TigerDuckTheme.courseColorVibrant(course.courseNo)
    val surface = MaterialTheme.colorScheme.surface
    val cardColor = if (TigerDuckTheme.isDarkMode) {
        color.copy(alpha = 0.70f).compositeOver(surface)
    } else {
        color.copy(alpha = 0.70f)
    }
    val progress = if (blockEndMinute > blockStartMinute) {
        ((currentMinute - blockStartMinute).toFloat() /
                (blockEndMinute - blockStartMinute)).coerceIn(0f, 1f)
    } else 0f
    val timeRange = "${formatHm(blockStartMinute)} - ${formatHm(blockEndMinute)}"

    Card(
        onClick = onClick,
        modifier = modifier.width(220.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = courseNameForDisplay(course.courseName, maxChars = 28),
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (course.classroom.isNotEmpty()) {
                    Text(
                        text = course.classroom,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = ContentAlpha.SECONDARY),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = timeRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = ContentAlpha.SECONDARY)
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
                    tint = Color.Gray.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatHm(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
