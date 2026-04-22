package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.component.ColorPickerSheet
import org.ntust.app.tigerduck.ui.component.CourseCard
import org.ntust.app.tigerduck.ui.component.CurrentClassCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.SyncIndicator
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableScreen(
    viewModel: ClassTableViewModel = hiltViewModel()
) {
    val courses by viewModel.courses.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val currentMinute by viewModel.currentMinute.collectAsState()
    val selectedCourse by viewModel.selectedCourse.collectAsState()
    val todayCourses = remember(courses, currentMinute) { viewModel.todayCourses }
    val ongoingCourse = remember(courses, currentMinute) { viewModel.ongoingCourse }
    val activePeriods = remember(courses) { viewModel.activePeriods }
    val activeWeekdays = remember(courses) { viewModel.activeWeekdays }
    var showAddCourse by remember { mutableStateOf(false) }
    var courseToRename by remember { mutableStateOf<Course?>(null) }
    var renameText by remember { mutableStateOf("") }
    var courseToRecolor by remember { mutableStateOf<Course?>(null) }
    var showCheckmark by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) {
        viewModel.syncCompleteEvent.collect {
            showCheckmark = true
            kotlinx.coroutines.delay(2000)
            showCheckmark = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar("無法連線，請檢查網路連線")
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    var pullRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading) {
        if (!isLoading) pullRefreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.refresh()
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            PageHeader(title = "課表") {
                SyncIndicator(isLoading = isLoading, showCheckmark = showCheckmark)
                IconButton(
                    onClick = { showAddCourse = true },
                    enabled = isLoggedIn
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "新增課程",
                        tint = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isLoggedIn) ContentAlpha.SECONDARY else ContentAlpha.DISABLED
                        )
                    )
                }
            }

            // Today's courses carousel — only meaningful when the user is
            // viewing the live semester. Past semesters are historical
            // records, so "現在課程 / 今日課程" don't apply there.
            val selectedSemester by viewModel.currentSemester.collectAsState()
            val isLiveSemester = selectedSemester == viewModel.liveSemesterCode
            if (isLiveSemester && todayCourses.isNotEmpty()) {
                SectionHeader(title = "今日課程")
                val today = java.util.Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).get(java.util.Calendar.DAY_OF_WEEK)
                val dayIndex = when (today) {
                    java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2
                    java.util.Calendar.WEDNESDAY -> 3; java.util.Calendar.THURSDAY -> 4
                    java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
                    else -> 7
                }
                val rowScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .horizontalScroll(rowScroll)
                        .height(IntrinsicSize.Max)
                        .padding(horizontal = 16.dp)
                ) {
                    ongoingCourse?.let { info ->
                        CurrentClassCard(
                            course = info.course,
                            blockStartMinute = info.startMinute,
                            blockEndMinute = info.endMinute,
                            currentMinute = currentMinute,
                            hasAssignment = viewModel.hasAssignment(info.course.courseNo),
                            onClick = {
                                viewModel.selectCourse(
                                    info.course,
                                    info.weekday,
                                    info.firstPeriodId
                                )
                            },
                            modifier = Modifier.fillMaxHeight()
                        )
                        Spacer(Modifier.width(24.dp))
                    }
                    todayCourses.forEachIndexed { index, course ->
                        val timeRange = remember(course, dayIndex) {
                            val periods = course.schedule[dayIndex]
                                ?.sortedBy { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                            if (!periods.isNullOrEmpty()) {
                                val first = AppConstants.PeriodTimes.mapping[periods.first()]
                                val last = AppConstants.PeriodTimes.mapping[periods.last()]
                                if (first != null && last != null) "${first.first}-${last.second}" else null
                            } else null
                        }
                        CourseCard(
                            course = course,
                            timeRange = timeRange,
                            hasAssignment = viewModel.hasAssignment(course.courseNo),
                            isFinished = viewModel.isCourseFinishedToday(course),
                            onClick = {
                                val firstPeriod = course.schedule[dayIndex]
                                    ?.minByOrNull { AppConstants.Periods.chronologicalOrder.indexOf(it) } ?: ""
                                viewModel.selectCourse(course, dayIndex, firstPeriod)
                            },
                            modifier = Modifier.fillMaxHeight()
                        )
                        if (index < todayCourses.lastIndex) {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }

            // Semester picker + credits row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SemesterPicker(
                    current = selectedSemester,
                    options = viewModel.availableSemesters,
                    labelFor = viewModel::displayLabel,
                    onPick = { viewModel.setSemester(it) }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${viewModel.totalCredits} 學分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                )
            }

            // Timetable
            if (activePeriods.isNotEmpty() && activeWeekdays.isNotEmpty() && courses.isNotEmpty()) {
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
                    },
                    onPickColor = { course ->
                        courseToRecolor = course
                    }
                )
            } else if (!isLoggedIn) {
                org.ntust.app.tigerduck.ui.component.EmptyStateView(
                    icon = Icons.Filled.Lock,
                    title = "尚未登入",
                    message = "請先登入以使用這項功能",
                )
            }

            Spacer(Modifier.height(32.dp))
        }

    }
    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box

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

    courseToRecolor?.let { course ->
        val isDark = TigerDuckTheme.isDarkMode
        val displayPalette = if (isDark) courseColorPaletteDark else courseColorPalette
        val currentColor = TigerDuckTheme.courseColor(course.courseNo)
        val usedByOthers = remember(courses, course.courseNo, isDark) {
            courses
                .asSequence()
                .filter { it.courseNo != course.courseNo }
                .map { TigerDuckTheme.courseColor(it.courseNo).toArgb() or 0xFF000000.toInt() }
                .toSet()
        }
        ColorPickerSheet(
            courseName = course.courseName,
            currentColor = currentColor,
            presetPalette = displayPalette,
            usedByOthers = usedByOthers,
            onApply = { picked ->
                val pickedArgb = picked.toArgb() or 0xFF000000.toInt()
                val displayIdx = displayPalette.indexOfFirst {
                    (it.toArgb() or 0xFF000000.toInt()) == pickedArgb
                }
                // Preset picks are stored as the canonical (light) hex so
                // switching theme later swaps automatically. Custom HSV picks
                // keep the exact color in both modes.
                val storedArgb = if (displayIdx >= 0) {
                    courseColorPalette[displayIdx].toArgb()
                } else {
                    picked.toArgb()
                }
                val hex = "#" + String.format("%06X", storedArgb and 0xFFFFFF)
                viewModel.updateCourseColor(course.courseNo, hex)
                courseToRecolor = null
            },
            onDismiss = { courseToRecolor = null }
        )
    }

    if (showAddCourse) {
        ModalBottomSheet(
            onDismissRequest = { showAddCourse = false }
        ) {
            val currentSemester by viewModel.currentSemester.collectAsState()
            AddCourseSheet(
                semester = currentSemester,
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
    onDelete: (Course) -> Unit = {},
    onPickColor: (Course) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
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
                                val cellBg = if (TigerDuckTheme.isDarkMode) {
                                    // Dark palette is pre-tuned to sit against the dark
                                    // surface, so render it directly rather than tinting
                                    // a vivid light-palette color at low alpha.
                                    TigerDuckTheme.courseColor(role.course.courseNo)
                                } else {
                                    TigerDuckTheme.courseColorVibrant(role.course.courseNo)
                                        .copy(alpha = 0.50f)
                                }
                                val cellTextColor = if (TigerDuckTheme.isDarkMode) {
                                    Color.White
                                } else {
                                    Color(0xFF1C1C1E)
                                }
                                val hasBadge = viewModel.hasAssignment(role.course.courseNo)
                                var showMenu by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight * role.spanCount)
                                        .absoluteOffset(x = x, y = y)
                                        .padding(1.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(cellBg)
                                        .combinedClickable(
                                            onClick = {
                                                viewModel.selectCourse(role.course, weekday, period.id)
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                showMenu = true
                                            }
                                        )
                                ) {
                                    Text(
                                        text = role.course.courseName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cellTextColor,
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
                                            tint = cellTextColor.copy(alpha = 0.7f)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重新命名") },
                                            onClick = {
                                                showMenu = false
                                                onRename(role.course)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("選擇顏色") },
                                            onClick = {
                                                showMenu = false
                                                onPickColor(role.course)
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

@Composable
private fun SemesterPicker(
    current: String,
    options: List<String>,
    labelFor: (String) -> String,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = labelFor(current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp)
        ) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = {
                        Text(
                            labelFor(code),
                            color = if (code == current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        expanded = false
                        onPick(code)
                    }
                )
            }
        }
    }
}
