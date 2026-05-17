package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.clock.AppClock
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
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
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
    viewModel: ClassTableViewModel = hiltViewModel(),
    onOpenSignInSettings: () -> Unit = {},
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
    var tripleConflictError by remember {
        mutableStateOf<ClassTableViewModel.TripleConflictError?>(
            null
        )
    }
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

                if (!isLoggedIn) {
                    org.ntust.app.tigerduck.ui.component.EmptyStateView(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.common_not_logged_in),
                        message = stringResource(R.string.common_login_required_feature),
                        onIconClick = onOpenSignInSettings,
                    )
                    Spacer(Modifier.height(32.dp))
                    return@Column
                }

                // Today's courses carousel — only meaningful when the user is
                // viewing the live semester. Past semesters are historical
                // records, so "現在課程 / 今日課程" don't apply there.
                val isLiveSemester = selectedSemester == viewModel.liveSemesterCode
                if (isLiveSemester && todayCourses.isNotEmpty()) {
                    SectionHeader(title = stringResource(R.string.home_section_today_courses))
                    val today =
                        AppClock.calendar().get(java.util.Calendar.DAY_OF_WEEK)
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
                                        ?.minByOrNull {
                                            AppConstants.Periods.chronologicalOrder.indexOf(
                                                it
                                            )
                                        } ?: ""
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
                        text = stringResource(
                            R.string.class_table_total_credits_value,
                            viewModel.totalCredits
                        ),
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
                            renameText = course.customCourseName ?: viewModel.defaultNameFor(course)
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
                }

                Spacer(Modifier.height(32.dp))
            }

        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box

    selectedCourse?.let { course ->
        val context = androidx.compose.ui.platform.LocalContext.current
        CourseDetailDialog(
            course = course,
            title = viewModel.selectedCourseFullName ?: course.displayName,
            classroom = course.classroom,
            timeRange = viewModel.selectedCourseTimeRange,
            assignments = viewModel.assignmentsFor(course.courseNo),
            moodleCourseId = viewModel.moodleCourseIdFor(course),
            onRename = {
                renameText = course.customCourseName ?: viewModel.defaultNameFor(course)
                courseToRename = course
                viewModel.clearSelection()
            },
            onOpenInMoodle = { id -> openCourseInMoodle(context, id) },
            onDismiss = { viewModel.clearSelection() },
        )
    }

    courseToRename?.let { course ->
        val liveCourse = courses.find { it.courseNo == course.courseNo } ?: course
        val defaultName = remember(liveCourse) { viewModel.defaultNameFor(liveCourse) }
        val trimmed = renameText.trim()
        val hasOverride = liveCourse.customCourseName != null
        // Revert is meaningful when a stored override exists OR the user has
        // typed away from the default; either way it restores the default.
        val canRevert = hasOverride || (trimmed.isNotEmpty() && trimmed != defaultName)
        AlertDialog(
            onDismissRequest = { courseToRename = null },
            title = { Text(stringResource(R.string.class_table_rename_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.class_table_course_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.class_table_rename_default_label,
                                defaultName,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = ContentAlpha.SECONDARY,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (hasOverride) {
                                    viewModel.revertCourseName(course.courseNo)
                                    courseToRename = null
                                } else {
                                    renameText = defaultName
                                }
                            },
                            enabled = canRevert,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.class_table_rename_revert),
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCustomCourseName(course.courseNo, renameText)
                    courseToRename = null
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { courseToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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
            courseName = course.displayName,
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
                        err.existingA.displayName,
                        err.existingB.displayName,
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
    val context = androidx.compose.ui.platform.LocalContext.current
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

    BoxWithConstraints(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)) {
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
            Box(
                modifier = Modifier
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
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = 0.3f
                                            )
                                        )
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
                                    onTap = {
                                        viewModel.selectCourse(
                                            role.course,
                                            weekday,
                                            period.id
                                        )
                                    },
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onDelete = onDelete,
                                )
                            }

                            is ClassTableViewModel.CellRole.ConflictStart -> {
                                ConflictCourseCell(
                                    cellRole = role,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    periodId = period.id,
                                    hasAssignmentA = viewModel.hasAssignment(role.courseA.courseNo),
                                    hasAssignmentB = viewModel.hasAssignment(role.courseB.courseNo),
                                    onPickConflict = onPickConflict,
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
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
    val currentLabel = labelFor(current)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .semantics(mergeDescendants = true) {
                    role = Role.DropdownList
                    contentDescription = currentLabel
                }
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = currentLabel,
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
    val assignmentLabel = stringResource(R.string.a11y_class_table_cell_assignment_indicator)
    val cellLabel = buildString {
        append(course.displayName)
        if (course.classroom.isNotBlank()) {
            append(", ")
            append(course.classroom)
        }
        if (hasAssignment) {
            append(". ")
            append(assignmentLabel)
        }
    }
    Box(
        modifier = Modifier
            .width(dayColWidth)
            .height(cellHeight * spanCount)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(cellBg)
            .semantics(mergeDescendants = true) {
                contentDescription = cellLabel
                role = Role.Button
            }
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    onLongPress()
                    showMenu = true
                },
            ),
    ) {
        ClassTableCourseNameText(
            text = course.displayName,
            color = cellTextColor,
            maxLines = if (spanCount >= 2) 3 else 2,
            modifier = Modifier
                .padding(2.dp)
                .align(Alignment.Center),
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
    cellRole: ClassTableViewModel.CellRole.ConflictStart,
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

    val overlapStart = maxOf(cellRole.offsetA, cellRole.offsetB)
    val overlapEnd = minOf(cellRole.offsetA + cellRole.spanA, cellRole.offsetB + cellRole.spanB)

    // Solo fractions are relative to each course's OWN span (= its Box height),
    // not the combined span.
    fun soloAbove(offset: Int, span: Int) =
        (overlapStart - offset).coerceAtLeast(0).toFloat() / span

    fun soloBelow(offset: Int, span: Int) =
        (offset + span - overlapEnd).coerceAtLeast(0).toFloat() / span

    val soloAboveA = soloAbove(cellRole.offsetA, cellRole.spanA)
    val soloBelowA = soloBelow(cellRole.offsetA, cellRole.spanA)
    val soloAboveB = soloAbove(cellRole.offsetB, cellRole.spanB)
    val soloBelowB = soloBelow(cellRole.offsetB, cellRole.spanB)

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
            .height(cellHeight * cellRole.combinedSpan)
            .absoluteOffset(x = x, y = y)
            .padding(1.dp),
    ) {
        // Pull the true padded size so child heights/offsets scale to it exactly.
        // Using a fixed `cellHeight * spanA` for each child causes its Box to
        // exceed the padded area by 2dp; Compose's overflow handling then
        // leaves a visible seam between the two shapes.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rowHeight = maxHeight / cellRole.combinedSpan
            val aTop = rowHeight * cellRole.offsetA
            val aHeight = rowHeight * cellRole.spanA
            val bTop = rowHeight * cellRole.offsetB
            val bHeight = rowHeight * cellRole.spanB
            val aBarFraction = (soloAboveA + 0.5f * (1f - soloAboveA - soloBelowA))
                .coerceAtLeast(0.1f)
            val bBarFraction = (soloBelowB + 0.5f * (1f - soloAboveB - soloBelowB))
                .coerceAtLeast(0.1f)
            val conflictPrefix = stringResource(R.string.a11y_class_table_conflict_prefix)
            val assignmentLabel = stringResource(R.string.a11y_class_table_cell_assignment_indicator)
            fun cellLabel(course: Course, hasAssignment: Boolean): String = buildString {
                append(conflictPrefix)
                append(": ")
                append(course.displayName)
                if (course.classroom.isNotBlank()) {
                    append(", ")
                    append(course.classroom)
                }
                if (hasAssignment) {
                    append(". ")
                    append(assignmentLabel)
                }
            }
            val labelA = cellLabel(cellRole.courseA, hasAssignmentA)
            val labelB = cellLabel(cellRole.courseB, hasAssignmentB)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(aHeight)
                    .absoluteOffset(x = 0.dp, y = aTop)
                    .clip(shapeA)
                    .background(bgFor(cellRole.courseA))
                    .semantics(mergeDescendants = true) {
                        contentDescription = labelA
                        role = Role.Button
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = indication,
                        onClick = { onPickConflict(cellRole.courseA, cellRole.courseB, weekday, periodId) },
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
                        text = cellRole.courseA.displayName,
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
                    .background(bgFor(cellRole.courseB))
                    .semantics(mergeDescendants = true) {
                        contentDescription = labelB
                        role = Role.Button
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = indication,
                        onClick = { onPickConflict(cellRole.courseA, cellRole.courseB, weekday, periodId) },
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
                        text = cellRole.courseB.displayName,
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
                listOf(cellRole.courseA, cellRole.courseB).forEachIndexed { idx, course ->
                    if (idx > 0) HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.class_table_rename_with_course,
                                    course.displayName,
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
                                    course.displayName,
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
                                    course.displayName,
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

/**
 * Course-detail popup styled after the iOS app: large title and a slim
 * course-colored bar at the top, then two emphasized cards displaying the
 * fields users glance at most (Classroom and Time), followed by the
 * remaining metadata as label/value rows and the outstanding-assignments
 * list. Rendered as a custom [Dialog] wrapping a Material 3 [Surface] so
 * the layout can flex beyond what [AlertDialog]'s title/text/buttons slots
 * allow, while keeping the same shape, container color, and tonal
 * elevation as other dialogs in the app (see [LoginSheet]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailDialog(
    course: Course,
    title: String,
    classroom: String,
    timeRange: String?,
    assignments: List<org.ntust.app.tigerduck.data.model.Assignment>,
    moodleCourseId: Int?,
    onRename: () -> Unit,
    onOpenInMoodle: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val courseColor = TigerDuckTheme.courseColor(course.courseNo)
    val dash = "—"
    val classroomValue = classroom.trim().ifEmpty { dash }
    val timeValue = timeRange?.takeIf { it.isNotBlank() } ?: dash

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Header: title + rename pencil, with a course-colored
                // accent bar pinned underneath so the two cards below read
                // as belonging to *this* course at a glance.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = onRename) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.class_table_rename_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (moodleCourseId != null) {
                            IconButton(onClick = { onOpenInMoodle(moodleCourseId) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(courseColor),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    EmphasisCard(
                        label = stringResource(R.string.course_detail_classroom_label),
                        value = classroomValue,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    EmphasisCard(
                        label = stringResource(R.string.course_detail_time_label),
                        value = timeValue,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow(
                        label = stringResource(R.string.course_detail_instructor_label),
                        value = course.instructor.ifBlank { dash },
                    )
                    InfoRow(
                        label = stringResource(R.string.course_detail_code_label),
                        value = course.courseNo,
                    )
                    InfoRow(
                        label = stringResource(R.string.course_detail_credits_label),
                        value = course.credits.toString(),
                    )
                    InfoRow(
                        label = stringResource(R.string.course_detail_enrollment_label),
                        value = "${course.enrolledCount} / ${course.maxCount}",
                    )
                }

                if (assignments.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.course_detail_incomplete_assignments),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        assignments.forEach { assignment ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    text = assignment.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

/**
 * Big-text card used for the two fields that earn visual emphasis
 * (classroom and time). Label sits small at the top-start; the value
 * renders large and centered so paired cards read as a single
 * at-a-glance unit. Background uses `surfaceContainerHighest` because
 * the host dialog already renders `surface` + 6dp tonal elevation —
 * which matches `surfaceContainerHigh` — so anything lower than
 * `surfaceContainerHighest` would blend into the dialog with no
 * visible contrast.
 */
@Composable
private fun EmphasisCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    // titleMedium (not titleLarge) so longer time strings like
    // "09:10 - 12:00" fit on one line without auto-shrinking, and both
    // cards in the pair use the same size for visual symmetry.
    val valueStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // weight(1f) on the value Box lets sibling cards in an
            // IntrinsicSize.Min row equalize on the taller card's
            // intrinsic height by letting this Box absorb the extra
            // vertical space, vertically centering the value inside it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value,
                    style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Open course id [moodleCourseId] in the Moodle Mobile app via the
 * `moodlemobile://` deep link envelope, falling back to the browser if
 * Moodle Mobile isn't installed or the OS refuses to route the intent.
 * Mirrors the assignment-row pattern in [HomeScreen.openAssignmentInMoodle]
 * so the two surfaces behave identically.
 */
private fun openCourseInMoodle(context: Context, moodleCourseId: Int) {
    val redirect = "/course/view.php?id=$moodleCourseId"
    val targets = listOf(
        "moodlemobile://https://moodle2.ntust.edu.tw?redirect=$redirect",
        "https://moodle2.ntust.edu.tw$redirect",
    )
    for (target in targets) {
        val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (opened) return
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}
