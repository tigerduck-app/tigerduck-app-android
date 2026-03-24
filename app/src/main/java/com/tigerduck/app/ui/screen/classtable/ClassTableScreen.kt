package com.tigerduck.app.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tigerduck.app.AppConstants
import com.tigerduck.app.data.model.Course
import com.tigerduck.app.ui.component.CourseCard
import com.tigerduck.app.ui.component.SectionHeader
import com.tigerduck.app.ui.theme.TigerDuckTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableScreen(
    viewModel: ClassTableViewModel = hiltViewModel()
) {
    val courses by viewModel.courses.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedCourse by viewModel.selectedCourse.collectAsState()
    val todayCourses = viewModel.todayCourses
    val activePeriods = viewModel.activePeriods
    val activeWeekdays = viewModel.activeWeekdays

    LaunchedEffect(Unit) { viewModel.load() }

    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isLoading,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "課表",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "共 ${viewModel.totalCredits} 學分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            // Today's courses
            if (todayCourses.isNotEmpty()) {
                SectionHeader(title = "今日課程")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(todayCourses) { course ->
                        CourseCard(
                            course = course,
                            hasAssignment = viewModel.hasAssignment(course.courseNo),
                            onClick = {
                                val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                                val dayIndex = when (today) {
                                    java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2
                                    java.util.Calendar.WEDNESDAY -> 3; java.util.Calendar.THURSDAY -> 4
                                    java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
                                    else -> 7
                                }
                                val firstPeriod = course.schedule[dayIndex]?.firstOrNull() ?: ""
                                viewModel.selectCourse(course, dayIndex, firstPeriod)
                            }
                        )
                    }
                }
            }

            // Timetable
            if (activePeriods.isNotEmpty() && activeWeekdays.isNotEmpty()) {
                TimetableGrid(
                    viewModel = viewModel,
                    weekdays = activeWeekdays,
                    periods = activePeriods
                )
            }

            Spacer(Modifier.height(32.dp))
        }

    }

    selectedCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { viewModel.clearSelection() },
            title = { Text(course.courseName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("課號：${course.courseNo}")
                    Text("講師：${course.instructor}")
                    Text("教室：${course.classroom}")
                    Text("學分：${course.credits}")
                    Text("修課人數：${course.enrolledCount}/${course.maxCount}")
                    viewModel.selectedCourseTimeRange?.let {
                        Text("上課時間：$it")
                    }
                    val assignments = viewModel.assignmentsFor(course.courseNo)
                    if (assignments.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("待繳作業", style = MaterialTheme.typography.titleSmall)
                        assignments.forEach { a ->
                            Text("• ${a.title}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSelection() }) { Text("關閉") }
            }
        )
    }
}

@Composable
private fun TimetableGrid(
    viewModel: ClassTableViewModel,
    weekdays: List<Int>,
    periods: List<com.tigerduck.app.data.model.TimetablePeriod>
) {
    val dayLabels = listOf("", "一", "二", "三", "四", "五", "六", "日")
    val cellHeight = 52.dp
    val periodColWidth = 36.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        val dayColWidth = (maxWidth - periodColWidth) / weekdays.size
        val totalHeight = cellHeight * periods.size

        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row {
                Box(modifier = Modifier.width(periodColWidth))
                weekdays.forEach { day ->
                    Box(
                        modifier = Modifier.width(dayColWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabels.getOrElse(day) { "$day" },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Grid body — use Box with absolute positioning so blocks can span rows
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
            ) {
                // Period labels (left column)
                periods.forEachIndexed { periodIndex, period ->
                    Column(
                        modifier = Modifier
                            .width(periodColWidth)
                            .height(cellHeight)
                            .absoluteOffset(x = 0.dp, y = cellHeight * periodIndex),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = period.id,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            fontSize = 12.sp
                        )
                        Text(
                            text = period.startTime,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                // Day cells
                weekdays.forEachIndexed { dayIndex, weekday ->
                    val x = periodColWidth + dayColWidth * dayIndex

                    periods.forEachIndexed { periodIndex, period ->
                        val y = cellHeight * periodIndex

                        when (val role = viewModel.cellRole(weekday, periodIndex)) {
                            is ClassTableViewModel.CellRole.Empty -> {
                                Box(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight)
                                        .absoluteOffset(x = x, y = y)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                )
                            }
                            is ClassTableViewModel.CellRole.BlockStart -> {
                                val color = TigerDuckTheme.courseColor(role.course.courseNo)
                                Box(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight * role.spanCount)
                                        .absoluteOffset(x = x, y = y)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(color.copy(alpha = 0.85f))
                                        .clickable {
                                            viewModel.selectCourse(role.course, weekday, period.id)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = role.course.courseName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        maxLines = if (role.spanCount >= 2) 3 else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(2.dp),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            is ClassTableViewModel.CellRole.BlockContinuation -> {
                                // Rendered as part of BlockStart — no cell needed
                            }
                        }
                    }
                }
            }
        }
    }
}
