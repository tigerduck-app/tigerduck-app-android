package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroomFrom

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
    val room = if (periodId != null) course.blockClassroomFrom(weekday, periodId) else course.classroom(weekday)
    CourseListRow(
        colorKey = course.courseNo,
        title = course.displayName,
        details = listOf(course.courseNo) + listOf(course.instructor, room).filter { it.isNotBlank() },
        modifier = Modifier.clickable { onPick(course) },
    )
}
