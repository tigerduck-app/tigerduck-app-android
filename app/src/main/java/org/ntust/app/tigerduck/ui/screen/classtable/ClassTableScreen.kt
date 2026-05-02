package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.ui.component.ColorPickerSheet
import org.ntust.app.tigerduck.ui.component.ConflictCoursePickerSheet
import org.ntust.app.tigerduck.ui.component.ConflictLOrientation
import org.ntust.app.tigerduck.ui.component.ConflictLShape
import org.ntust.app.tigerduck.ui.component.CourseCard
import org.ntust.app.tigerduck.ui.component.CurrentClassCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.SyncIndicator
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.isEnglishUiLanguage
import org.ntust.app.tigerduck.ui.component.middleEllipsize
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark

private data class ConflictPickerTarget(
    val courseA: Course,
    val courseB: Course,
    val weekday: Int,
    val periodId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableScreen(
    viewModel: ClassTableViewModel = hiltViewModel()
) {
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val currentMinute by viewModel.currentMinute.collectAsStateWithLifecycle()
    val selectedCourse by viewModel.selectedCourse.collectAsStateWithLifecycle()
    // Hoisted alongside the other top-level subscriptions: a second
    // collectAsStateWithLifecycle for the same flow inside a conditional
    // body invites readers from the wrong scope and is fragile if the
    // condition changes.
    val selectedSemester by viewModel.currentSemester.collectAsStateWithLifecycle()
    val todayCourses = remember(courses, currentMinute) { viewModel.todayCourses }
    val ongoingCourses = remember(courses, currentMinute) { viewModel.ongoingCourses }
    val activePeriods = remember(courses) { viewModel.activePeriods }
    val activeWeekdays = remember(courses) { viewModel.activeWeekdays }
    var showAddCourse by remember { mutableStateOf(false) }
    var courseToRename by remember { mutableStateOf<Course?>(null) }
    var renameText by remember { mutableStateOf("") }
    var courseToRecolor by remember { mutableStateOf<Course?>(null) }
    var showCheckmark by remember { mutableStateOf(false) }
    var conflictPicker by remember { mutableStateOf<ConflictPickerTarget?>(null) }
    var tripleConflictError by remember { mutableStateOf<ClassTableViewModel.TripleConflictError?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val errorNetworkUnavailable = stringResource(R.string.error_network_unavailable)
    val refreshingMessage = stringResource(R.string.refreshing_message)
    val weekdayShortLabels = listOf(
        "",
        stringResource(R.string.weekday_mon_short),
        stringResource(R.string.weekday_tue_short),
        stringResource(R.string.weekday_wed_short),
        stringResource(R.string.weekday_thu_short),
        stringResource(R.string.weekday_fri_short),
        stringResource(R.string.weekday_sat_short),
        stringResource(R.string.weekday_sun_short),
    )

    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(viewModel) {
        viewModel.syncCompleteEvent.collect {
            showCheckmark = true
            kotlinx.coroutines.delay(2000)
            showCheckmark = false
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(errorNetworkUnavailable)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.tripleConflictEvent.collect { tripleConflictError = it }
    }

    var pullProgress by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
    TigerPullToRefresh(
        isRefreshing = isLoading,
        onRefresh = { viewModel.refresh() },
        onDragProgress = { pullProgress = it },
        modifier = Modifier.fillMaxSize(),
        refreshingMessage = refreshingMessage,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            PageHeader(title = stringResource(R.string.feature_class_table)) {
                SyncIndicator(
                    isLoading = isLoading,
                    showCheckmark = showCheckmark,
                    dragProgress = pullProgress,
                )
                IconButton(
                    onClick = { showAddCourse = true },
                    enabled = isLoggedIn
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_course_title),
                        tint = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isLoggedIn) ContentAlpha.SECONDARY else ContentAlpha.DISABLED
                        )
                    )
                }
            }

            // Today's courses carousel — only meaningful when the user is
            // viewing the live semester. Past semesters are historical
            // records, so "現在課程 / 今日課程" don't apply there.
            val isLiveSemester = selectedSemester == viewModel.liveSemesterCode
            if (isLiveSemester && todayCourses.isNotEmpty()) {
                SectionHeader(title = stringResource(R.string.home_section_today_courses))
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
                    ongoingCourses.forEachIndexed { idx, info ->
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
                        if (idx < ongoingCourses.lastIndex) {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                    if (ongoingCourses.isNotEmpty()) {
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
                    text = stringResource(R.string.class_table_total_credits_value, viewModel.totalCredits),
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
                    },
                    onPickConflict = { a, b, weekday, periodId ->
                        conflictPicker = ConflictPickerTarget(a, b, weekday, periodId)
                    },
                )
            } else if (!isLoggedIn) {
                org.ntust.app.tigerduck.ui.component.EmptyStateView(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.common_not_logged_in),
                    message = stringResource(R.string.common_login_required_feature),
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
            title = { Text(viewModel.selectedCourseFullName ?: course.courseName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.class_table_course_no_value, course.courseNo))
                    Text(stringResource(R.string.course_instructor_value, course.instructor))
                    Text(stringResource(R.string.course_classroom_value, course.classroom))
                    Text(stringResource(R.string.course_credits_value, course.credits))
                    Text(
                        stringResource(
                            R.string.class_table_enrolled_count_value,
                            course.enrolledCount,
                            course.maxCount,
                        )
                    )
                    viewModel.selectedCourseTimeRange?.let {
                        Text(stringResource(R.string.class_table_course_time_value, it))
                    }
                    val assignments = viewModel.assignmentsFor(course.courseNo)
                    if (assignments.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.home_pending_assignments),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        assignments.forEach { a ->
                            Text("• ${a.title}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSelection() }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    courseToRename?.let { course ->
        AlertDialog(
            onDismissRequest = { courseToRename = null },
            title = { Text(stringResource(R.string.class_table_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.class_table_course_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCourse(course.courseNo, renameText)
                    courseToRename = null
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { courseToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
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

    conflictPicker?.let { target ->
        ConflictCoursePickerSheet(
            courseA = target.courseA,
            courseB = target.courseB,
            onPick = { picked ->
                viewModel.selectCourse(picked, target.weekday, target.periodId)
                conflictPicker = null
            },
            onDismiss = { conflictPicker = null },
        )
    }

    tripleConflictError?.let { err ->
        val dayLabel = weekdayShortLabels.getOrElse(err.weekday) { err.weekday.toString() }
        AlertDialog(
            onDismissRequest = { tripleConflictError = null },
            title = { Text(stringResource(R.string.class_table_conflict_add_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.class_table_conflict_add_failed_message,
                        err.newCourseName,
                        dayLabel,
                        err.periodId,
                        err.existingA.courseName,
                        err.existingB.courseName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { tripleConflictError = null }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
        )
    }

    if (showAddCourse) {
        val addCourseSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showAddCourse = false },
            sheetState = addCourseSheetState
        ) {
            val currentSemester by viewModel.currentSemester.collectAsStateWithLifecycle()
            AddCourseSheet(
                semester = currentSemester,
                existingCourseNos = viewModel.existingCourseNos,
                courseService = viewModel.courseService,
                sheetState = addCourseSheetState,
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
    onPickColor: (Course) -> Unit = {},
    onPickConflict: (Course, Course, Int, String) -> Unit = { _, _, _, _ -> },
) {
    val haptic = LocalHapticFeedback.current
    val dayLabels = listOf(
        "",
        stringResource(R.string.weekday_mon_short),
        stringResource(R.string.weekday_tue_short),
        stringResource(R.string.weekday_wed_short),
        stringResource(R.string.weekday_thu_short),
        stringResource(R.string.weekday_fri_short),
        stringResource(R.string.weekday_sat_short),
        stringResource(R.string.weekday_sun_short),
    )
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
                            is ClassTableViewModel.CellRole.SoloStart -> {
                                SoloCourseCell(
                                    course = role.course,
                                    spanCount = role.spanCount,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    hasAssignment = viewModel.hasAssignment(role.course.courseNo),
                                    onTap = { viewModel.selectCourse(role.course, weekday, period.id) },
                                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onDelete = onDelete,
                                )
                            }
                            is ClassTableViewModel.CellRole.ConflictStart -> {
                                ConflictCourseCell(
                                    role = role,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    periodId = period.id,
                                    hasAssignmentA = viewModel.hasAssignment(role.courseA.courseNo),
                                    hasAssignmentB = viewModel.hasAssignment(role.courseB.courseNo),
                                    onPickConflict = onPickConflict,
                                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onDelete = onDelete,
                                )
                            }
                            is ClassTableViewModel.CellRole.Skip -> {
                                // Rendered as part of an earlier SoloStart / ConflictStart
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SoloCourseCell(
    course: Course,
    spanCount: Int,
    dayColWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    hasAssignment: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onDelete: (Course) -> Unit,
) {
    val cellBg = if (TigerDuckTheme.isDarkMode) {
        TigerDuckTheme.courseColor(course.courseNo)
    } else {
        TigerDuckTheme.courseColorVibrant(course.courseNo).copy(alpha = 0.50f)
    }
    val cellTextColor = if (TigerDuckTheme.isDarkMode) Color.White else Color(0xFF1C1C1E)
    var showMenu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * spanCount)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(cellBg)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    onLongPress()
                    showMenu = true
                },
            ),
    ) {
        ClassTableCourseNameText(
            text = course.courseName,
            color = cellTextColor,
            maxLines = if (spanCount >= 2) 3 else 2,
            modifier = Modifier.padding(2.dp).align(Alignment.Center),
        )
        if (hasAssignment) {
            Icon(
                imageVector = Icons.Filled.Book,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(12.dp),
                tint = cellTextColor.copy(alpha = 0.7f),
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(12.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.class_table_rename_title)) },
                onClick = { showMenu = false; onRename(course) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.class_table_pick_color)) },
                onClick = { showMenu = false; onPickColor(course) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.class_table_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { showMenu = false; onDelete(course) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConflictCourseCell(
    role: ClassTableViewModel.CellRole.ConflictStart,
    dayColWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    weekday: Int,
    periodId: String,
    hasAssignmentA: Boolean,
    hasAssignmentB: Boolean,
    onPickConflict: (Course, Course, Int, String) -> Unit,
    onLongPress: () -> Unit,
    onRename: (Course) -> Unit,
    onPickColor: (Course) -> Unit,
    onDelete: (Course) -> Unit,
) {
    val textColor = if (TigerDuckTheme.isDarkMode) Color.White else Color(0xFF1C1C1E)
    fun bgFor(course: Course) = if (TigerDuckTheme.isDarkMode) {
        TigerDuckTheme.courseColor(course.courseNo)
    } else {
        TigerDuckTheme.courseColorVibrant(course.courseNo).copy(alpha = 0.50f)
    }

    val overlapStart = maxOf(role.offsetA, role.offsetB)
    val overlapEnd = minOf(role.offsetA + role.spanA, role.offsetB + role.spanB)

    // Solo fractions are relative to each course's OWN span (= its Box height),
    // not the combined span.
    fun soloAbove(offset: Int, span: Int) =
        (overlapStart - offset).coerceAtLeast(0).toFloat() / span
    fun soloBelow(offset: Int, span: Int) =
        (offset + span - overlapEnd).coerceAtLeast(0).toFloat() / span

    val soloAboveA = soloAbove(role.offsetA, role.spanA)
    val soloBelowA = soloBelow(role.offsetA, role.spanA)
    val soloAboveB = soloAbove(role.offsetB, role.spanB)
    val soloBelowB = soloBelow(role.offsetB, role.spanB)

    // Pure overlap at an edge = neither course has solo at that edge. There
    // both shapes have convex corners pointing the same way, so only sharp
    // corners can touch without a gap.
    val sharpTop = soloAboveA == 0f && soloAboveB == 0f
    val sharpBottom = soloBelowA == 0f && soloBelowB == 0f

    val shapeA = ConflictLShape(
        orientation = ConflictLOrientation.TopBarRightTail,
        soloAboveFraction = soloAboveA,
        soloBelowFraction = soloBelowA,
        sharpTopOuter = sharpTop,
        sharpBottomOuter = sharpBottom,
    )
    val shapeB = ConflictLShape(
        orientation = ConflictLOrientation.LeftTailBottomBar,
        soloAboveFraction = soloAboveB,
        soloBelowFraction = soloBelowB,
        sharpTopOuter = sharpTop,
        sharpBottomOuter = sharpBottom,
    )

    var showMenu by remember { mutableStateOf(false) }
    // Shared so a press on EITHER course triggers the ripple on BOTH — the
    // menu applies to both, so the press feedback should too.
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * role.combinedSpan)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp),
    ) {
        // Pull the true padded size so child heights/offsets scale to it exactly.
        // Using a fixed `cellHeight * spanA` for each child causes its Box to
        // exceed the padded area by 2dp; Compose's overflow handling then
        // leaves a visible seam between the two shapes.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rowHeight = maxHeight / role.combinedSpan
        val aTop = rowHeight * role.offsetA
        val aHeight = rowHeight * role.spanA
        val bTop = rowHeight * role.offsetB
        val bHeight = rowHeight * role.spanB
        val aBarFraction = (soloAboveA + 0.5f * (1f - soloAboveA - soloBelowA))
            .coerceAtLeast(0.1f)
        val bBarFraction = (soloBelowB + 0.5f * (1f - soloAboveB - soloBelowB))
            .coerceAtLeast(0.1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(aHeight)
                .absoluteOffset(x = 0.dp, y = aTop)
                .clip(shapeA)
                .background(bgFor(role.courseA))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = indication,
                    onClick = { onPickConflict(role.courseA, role.courseB, weekday, periodId) },
                    onLongClick = { onLongPress(); showMenu = true },
                ),
        ) {
            // Course name uses the full width of the bar rectangle.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(1f - 0.28f)
                    .fillMaxHeight(aBarFraction)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClassTableCourseNameText(
                    text = role.courseA.courseName,
                    color = textColor,
                    maxLines = 2,
                )
            }
            if (hasAssignmentA) {
                Icon(
                    imageVector = Icons.Filled.Book,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(10.dp),
                    tint = textColor.copy(alpha = 0.7f),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bHeight)
                .absoluteOffset(x = 0.dp, y = bTop)
                .clip(shapeB)
                .background(bgFor(role.courseB))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = indication,
                    onClick = { onPickConflict(role.courseA, role.courseB, weekday, periodId) },
                    onLongClick = { onLongPress(); showMenu = true },
                ),
        ) {
            // Course name uses the full width of the bar rectangle.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(1f - 0.28f)
                    .fillMaxHeight(bBarFraction)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClassTableCourseNameText(
                    text = role.courseB.courseName,
                    color = textColor,
                    maxLines = 2,
                )
            }
            if (hasAssignmentB) {
                Icon(
                    imageVector = Icons.Filled.Book,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .size(10.dp),
                    tint = textColor.copy(alpha = 0.7f),
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(12.dp),
        ) {
            listOf(role.courseA, role.courseB).forEachIndexed { idx, course ->
                if (idx > 0) HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.class_table_rename_with_course,
                                course.courseName,
                            )
                        )
                    },
                    onClick = { showMenu = false; onRename(course) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.class_table_pick_color_with_course,
                                course.courseName,
                            )
                        )
                    },
                    onClick = { showMenu = false; onPickColor(course) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.class_table_delete_with_course,
                                course.courseName,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { showMenu = false; onDelete(course) },
                )
            }
        }
        } // BoxWithConstraints
    }
}

@Composable
private fun ClassTableCourseNameText(
    text: String,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val useMiddle = isEnglishUiLanguage()
    var displayText by remember(text, useMiddle) { mutableStateOf(text) }
    Text(
        text = displayText,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        fontSize = 10.sp,
        onTextLayout = { layout ->
            if (!useMiddle) {
                if (displayText != text) displayText = text
                return@Text
            }
            if (!layout.hasVisualOverflow) return@Text
            val capacity = layout.getLineEnd((maxLines - 1).coerceAtLeast(0), visibleEnd = true)
            val next = middleEllipsize(text, capacity.coerceAtLeast(5))
            if (next != displayText) displayText = next
        }
    )
}
