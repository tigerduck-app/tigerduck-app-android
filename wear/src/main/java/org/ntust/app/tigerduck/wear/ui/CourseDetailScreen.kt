package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.wear.R
import org.ntust.app.tigerduck.wear.ui.theme.LocalScreenPadding

@Composable
fun CourseDetailScreen(course: Course) {
    val pad = LocalScreenPadding.current
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                ListHeader { Text(stringResource(R.string.course_detail_title)) }
            }
            item { Text(text = course.courseName) }
            item { Spacer(Modifier.height(2.dp)) }
            item {
                LabelValue(
                    label = stringResource(R.string.course_detail_classroom),
                    value = course.classroom,
                )
            }
            item {
                LabelValue(
                    label = stringResource(R.string.course_detail_instructor),
                    value = course.instructor,
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = androidx.compose.ui.graphics.Color.Gray)
        Text(text = value)
    }
}
