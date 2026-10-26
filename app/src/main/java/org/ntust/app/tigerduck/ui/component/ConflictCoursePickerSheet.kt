package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroomFrom
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictCoursePickerSheet(
    courseA: Course,
    courseB: Course,
    weekday: Int,
    /** The tapped tile's first period, which picks each course's block and so its room. */
    periodId: String? = null,
    onPick: (Course) -> Unit,
    onDismiss: () -> Unit,
) {
    DetentSheet(onDismissed = onDismiss, halfHeight = true) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.conflict_course_picker_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            ConflictCourseRow(courseA, weekday, periodId, onPick)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ConflictCourseRow(courseB, weekday, periodId, onPick)
        }
    }
}

@Composable
private fun ConflictCourseRow(course: Course, weekday: Int, periodId: String?, onPick: (Course) -> Unit) {
    val swatch = LocalVisualStylePolicy.current.courseAccentColor(
        courseColor = TigerDuckTheme.courseColorVibrant(course.courseNo),
        tint = TigerDuckTheme.courseColor(course.courseNo),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(course) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(swatch),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                course.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            val subtitle = buildString {
                append(course.courseNo)
                if (course.instructor.isNotBlank()) append(" · ${course.instructor}")
                val room = if (periodId != null) course.blockClassroomFrom(weekday, periodId) else course.classroom(weekday)
                if (room.isNotBlank()) append(" · $room")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
    }
}
