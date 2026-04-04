package org.ntust.app.tigerduck.ui.screen.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.component.*
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sections by viewModel.sections.collectAsState()
    val todayCourses by viewModel.todayCourses.collectAsState()
    val upcomingAssignments by viewModel.upcomingAssignments.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedCourse by viewModel.selectedCourse.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isLoading,
        onRefresh = { viewModel.refresh() },
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isRefreshing = isLoading,
                state = pullRefreshState
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = greetingText(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            items(sections) { section ->
                HomeSectionContent(
                    section = section,
                    todayCourses = todayCourses,
                    upcomingAssignments = upcomingAssignments,
                    hasUnfinishedAssignment = viewModel::hasUnfinishedAssignment,
                    showAbsoluteTime = false,
                    onCourseClick = { viewModel.selectCourse(it) },
                    onAssignmentClick = { openAssignmentInMoodle(context, it) }
                )
            }
        }

    }

    selectedCourse?.let { course ->
        CourseDetailDialog(
            course = course,
            assignments = viewModel.assignmentsFor(course.courseNo),
            onDismiss = { viewModel.selectCourse(null) }
        )
    }
}

@Composable
private fun HomeSectionContent(
    section: HomeSection,
    todayCourses: List<Course>,
    upcomingAssignments: List<Assignment>,
    hasUnfinishedAssignment: (String) -> Boolean,
    showAbsoluteTime: Boolean,
    onCourseClick: (Course) -> Unit,
    onAssignmentClick: (Assignment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = section.title)

        when (section.type) {
            HomeSection.HomeSectionType.TODAY_COURSES -> {
                if (todayCourses.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Filled.Book,
                        title = "今日沒有課程",
                        message = "好好休息吧"
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(todayCourses) { course ->
                            CourseCard(
                                course = course,
                                hasAssignment = hasUnfinishedAssignment(course.courseNo),
                                onClick = { onCourseClick(course) }
                            )
                        }
                    }
                }
            }

            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> {
                if (upcomingAssignments.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Filled.CheckCircle,
                        title = "一切順利",
                        message = "沒有待辦作業"
                    )
                } else {
                    val topAssignments = upcomingAssignments.take(5)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        topAssignments.forEachIndexed { index, assignment ->
                            AssignmentItem(
                                assignment = assignment,
                                showAbsoluteTime = showAbsoluteTime,
                                onClick = { onAssignmentClick(assignment) }
                            )
                            if (index < topAssignments.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }

            HomeSection.HomeSectionType.QUICK_WIDGETS,
            HomeSection.HomeSectionType.CUSTOM -> {
                if (section.widgets.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(section.widgets) { widget ->
                            Card(
                                modifier = Modifier.size(80.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = widget.feature.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = widget.feature.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseDetailDialog(
    course: Course,
    assignments: List<Assignment>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.courseName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("講師：${course.instructor}", style = MaterialTheme.typography.bodyMedium)
                Text("教室：${course.classroom}", style = MaterialTheme.typography.bodyMedium)
                Text("學分：${course.credits}", style = MaterialTheme.typography.bodyMedium)
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
            TextButton(onClick = onDismiss) { Text("關閉") }
        }
    )
}

private fun greetingText(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "深夜好"
        hour < 12 -> "早安"
        hour < 18 -> "午安"
        else -> "晚安"
    }
}

private fun openAssignmentInMoodle(context: Context, assignment: Assignment) {
    val targets = listOfNotNull(assignment.moodleDeepLink, assignment.moodleUrl)
    for (target in targets) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching {
            context.startActivity(intent)
        }.isSuccess
        if (opened) {
            return
        }
    }
}

