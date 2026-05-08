package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import org.ntust.app.tigerduck.shared.Course

@Composable
fun CourseDetailScreen(course: Course) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        Text(text = course.courseName)
        Text(text = course.classroom)
        Text(text = course.instructor)
    }
}
