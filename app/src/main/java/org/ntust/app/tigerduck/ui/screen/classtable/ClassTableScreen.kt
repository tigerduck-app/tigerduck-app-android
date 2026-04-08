package org.ntust.app.tigerduck.ui.screen.classtable

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.component.CourseCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableScreen(
    viewModel: ClassTableViewModel = hiltViewModel()
) {
    val courses by viewModel.courses.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    viewModel.currentMinute.collectAsState() // drives recomposition when a class ends
    val selectedCourse by viewModel.selectedCourse.collectAsState()
    val todayCourses = viewModel.todayCourses
    val activePeriods = viewModel.activePeriods
    val activeWeekdays = viewModel.activeWeekdays
    var showAddCourse by remember { mutableStateOf(false) }
    var courseToRename by remember { mutableStateOf<Course?>(null) }
    var renameText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) {
        for (event in viewModel.noNetworkEvent) {
            snackbarHostState.showSnackbar("無法連線，請檢查網路連線")
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    var pullRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(pullRefreshing) {
        if (pullRefreshing) {
            kotlinx.coroutines.delay(1000)
            pullRefreshing = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { scaffoldPadding ->
    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.refresh()
        },
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Title
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
                IconButton(onClick = { showAddCourse = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "新增課程",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Today's courses carousel
            if (todayCourses.isNotEmpty()) {
                SectionHeader(title = "今日課程")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    items(todayCourses) { course ->
                        CourseCard(
                            course = course,
                            hasAssignment = viewModel.hasAssignment(course.courseNo),
                            isFinished = viewModel.isCourseFinishedToday(course),
                            onClick = {
                                val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                                val dayIndex = when (today) {
                                    java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2
                                    java.util.Calendar.WEDNESDAY -> 3; java.util.Calendar.THURSDAY -> 4
                                    java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
                                    else -> 7
                                }
                                val firstPeriod = course.schedule[dayIndex]
                                    ?.minByOrNull { AppConstants.Periods.chronologicalOrder.indexOf(it) } ?: ""
                                viewModel.selectCourse(course, dayIndex, firstPeriod)
                            }
                        )
                    }
                }
            }

            // Semester + credits row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "學期選擇功能即將上線",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${viewModel.totalCredits} 學分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Timetable
            if (activePeriods.isNotEmpty() && activeWeekdays.isNotEmpty()) {
                TimetableGrid(
                    viewModel = viewModel,
                    weekdays = activeWeekdays,
                    periods = activePeriods,
                    onRename = { course ->
                        renameText = course.courseName
                        courseToRename = course
                    },
                    onDelete = { course ->
                        viewModel.deleteCourse(course.courseNo)
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }

    }
    } // Scaffold

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

    courseToRename?.let { course ->
        AlertDialog(
            onDismissRequest = { courseToRename = null },
            title = { Text("重新命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("課程名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCourse(course.courseNo, renameText)
                    courseToRename = null
                }) { Text("確認") }
            },
            dismissButton = {
                TextButton(onClick = { courseToRename = null }) { Text("取消") }
            }
        )
    }

    if (showAddCourse) {
        ModalBottomSheet(
            onDismissRequest = { showAddCourse = false }
        ) {
            AddCourseSheet(
                semester = viewModel.currentSemester.collectAsState().value,
                existingCourseNos = viewModel.existingCourseNos,
                courseService = viewModel.courseService,
                onAdd = { viewModel.addCourse(it) },
                onDismiss = { showAddCourse = false }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimetableGrid(
    viewModel: ClassTableViewModel,
    weekdays: List<Int>,
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    onRename: (Course) -> Unit = {},
    onDelete: (Course) -> Unit = {}
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
                                val hasBadge = viewModel.hasAssignment(role.course.courseNo)
                                var showMenu by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight * role.spanCount)
                                        .absoluteOffset(x = x, y = y)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(color.copy(alpha = 0.85f))
                                        .combinedClickable(
                                            onClick = {
                                                viewModel.selectCourse(role.course, weekday, period.id)
                                            },
                                            onLongClick = { showMenu = true }
                                        )
                                ) {
                                    Text(
                                        text = role.course.courseName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        maxLines = if (role.spanCount >= 2) 3 else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(2.dp).align(Alignment.Center),
                                        fontSize = 10.sp
                                    )
                                    if (hasBadge) {
                                        Icon(
                                            imageVector = Icons.Filled.Book,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(3.dp)
                                                .size(12.dp),
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重新命名") },
                                            onClick = {
                                                showMenu = false
                                                onRename(role.course)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("刪除", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                showMenu = false
                                                onDelete(role.course)
                                            }
                                        )
                                    }
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
