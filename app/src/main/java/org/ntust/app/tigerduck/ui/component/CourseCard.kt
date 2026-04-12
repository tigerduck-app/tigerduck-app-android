package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

@Composable
fun CourseCard(
    course: Course,
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
        Box {
            Row(modifier = Modifier.padding(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.courseName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = course.instructor,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY * textAlpha)
                    )
                    if (course.classroom.isNotEmpty()) {
                        Text(
                            text = course.classroom,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY * textAlpha)
                        )
                    }
                }
            }

            if (hasAssignment) {
                Icon(
                    imageVector = Icons.Filled.Book,
                    contentDescription = "有作業",
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
