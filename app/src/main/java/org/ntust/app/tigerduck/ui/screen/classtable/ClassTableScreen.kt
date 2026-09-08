// Screen shell for 課表: scaffold, top bar, semester picker, and the
// dialog/sheet state the grid raises back up. The grid itself and the
// individual cells live in sibling files.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import org.ntust.app.tigerduck.ui.component.CurrentClassCardWidth
import org.ntust.app.tigerduck.ui.component.CourseCardWidth
import org.ntust.app.tigerduck.ui.component.CourseCardGap
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.ColorPickerSheet
import org.ntust.app.tigerduck.ui.component.ConflictCoursePickerSheet
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.component.CourseCard
import org.ntust.app.tigerduck.ui.component.CurrentClassCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
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

/** The carousel's start/end inset, inside the scrollable content. */
private val CAROUSEL_EDGE_INSET = 16.dp

/**
 * How much of the preceding card stays visible when the row scrolls itself to
 * the ongoing class. Past the 12dp gap this leaves roughly 24dp of the card
 * before it — enough to read as "there is more to the left" without stealing
 * width from the class actually happening.
 */
private val CAROUSEL_ONGOING_PEEK = 36.dp

/**
 * Where the today's-courses row should sit so the ongoing class leads it.
 *
 * [isOngoing] is per card, in display order, because a card's width depends on
 * which kind it is — two classes can overlap, so a wide ongoing card can sit
 * before the one we are scrolling to.
 *
 * Returns 0 when nothing is ongoing ([firstOngoingIndex] < 0) or when the
 * ongoing class is already first: the day should then be read from its start,
 * not nudged off the edge.
 */
internal fun carouselScrollTarget(isOngoing: List<Boolean>, firstOngoingIndex: Int): Dp {
    if (firstOngoingIndex <= 0) return 0.dp
    var x = 0.dp
    for (i in 0 until firstOngoingIndex) {
        x += if (isOngoing[i]) CurrentClassCardWidth else CourseCardWidth
        x += CourseCardGap
    }
    // The row's own start inset sits inside the scrollable content, so it
    // counts toward the card's position.
    return (CAROUSEL_EDGE_INSET + x - CAROUSEL_ONGOING_PEEK).coerceAtLeast(0.dp)
}

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
    // Collected as snapshot state (rather than reading the ViewModel's raw
    // StateFlow.value) so the badge icons recompose when the assignments
    // fetch completes after the courses fetch — a raw read registers no
    // Compose dependency, leaving badges stale until the next courses update.
    val allAssignments by viewModel.assignments.collectAsStateWithLifecycle()
    val courseNosWithAssignments = remember(allAssignments) {
        allAssignments.filterNot { it.isCompleted }.mapTo(mutableSetOf()) { it.courseNo }
    }
    // Hoisted alongside the other top-level subscriptions: a second
    // collectAsStateWithLifecycle for the same flow inside a conditional
    // body invites readers from the wrong scope and is fragile if the
    // condition changes.
    val selectedSemester by viewModel.currentSemester.collectAsStateWithLifecycle()
    val availableSemesters by viewModel.availableSemesters.collectAsStateWithLifecycle()
    // liveSemesterCourses is what the carousel reads while the picker is on
    // some other term, so it has to key these too — otherwise the strip stays
    // empty until something unrelated moves `courses`.
    val liveSemesterCourses by viewModel.liveSemesterCourses.collectAsStateWithLifecycle()
    val todayCourses = remember(courses, liveSemesterCourses, currentMinute) {
        viewModel.todayCourses
    }
    val ongoingCourses = remember(courses, liveSemesterCourses, currentMinute) {
        viewModel.ongoingCourses
    }
    // Keyed on the preference too: it is read inside the getter, so a flip
    // in Settings has to invalidate this or the grid keeps the old rows
    // until the course list happens to change.
    val alwaysShowPeriodsABC by viewModel.alwaysShowPeriodsABC.collectAsStateWithLifecycle()
    val activePeriods = remember(courses, alwaysShowPeriodsABC) { viewModel.activePeriods }
    val activeWeekdays = remember(courses) { viewModel.activeWeekdays }
    var showAddCourse by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var courseToRename by remember { mutableStateOf<Course?>(null) }
    var renameText by remember { mutableStateOf("") }
    var courseToRecolor by remember { mutableStateOf<Course?>(null) }
    var conflictPicker by remember { mutableStateOf<ConflictPickerTarget?>(null) }
    var tripleConflictError by remember {
        mutableStateOf<TripleConflictError?>(
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
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(errorNetworkUnavailable)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.tripleConflictEvent.collect { tripleConflictError = it }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = refreshingMessage,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                PageHeader(title = stringResource(R.string.feature_class_table)) {
                    SyncStatusDot(
                        servers = listOf(
                            ServerKind.MOODLE,
                            ServerKind.COURSE_SELECTION,
                            ServerKind.BACKEND,
                        ),
                        isLoading = isLoading,
                    )
                    IconButton(
                        onClick = { showResetConfirm = true },
                        enabled = isLoggedIn
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.class_table_reset_title),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (isLoggedIn) ContentAlpha.SECONDARY else ContentAlpha.DISABLED
                            )
                        )
                    }
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
                        title = stringResource(R.string.common_not_signed_in),
                        message = stringResource(R.string.common_sign_in_required_feature),
                        onIconClick = onOpenSignInSettings,
                    )
                    Spacer(Modifier.height(32.dp))
                    return@Column
                }

                // Today's courses carousel. Deliberately not scoped to the
                // selected semester: "what do I have today" is the same
                // question whichever term's grid the user opened, so the strip
                // keeps answering it while they look something up in an old
                // one. It reads the live term's roster, not the selected one —
                // see ClassTableViewModel.liveCourses — so an old timetable
                // can never present a class from two years ago as ongoing.
                if (todayCourses.isNotEmpty()) {
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

                    // The ongoing class is rendered in its own place in the
                    // day, in the ongoing style, rather than lifted to the
                    // front of the row. Lifting it showed the same class
                    // twice — once as the big card, once again further along
                    // as an ordinary one — and threw away the thing the
                    // carousel is for: where you are in today's sequence.
                    val ongoingByNo = remember(ongoingCourses) {
                        ongoingCourses.associateBy { it.course.courseNo }
                    }
                    val firstOngoingIndex = remember(todayCourses, ongoingByNo) {
                        todayCourses.indexOfFirst { it.courseNo in ongoingByNo }
                    }

                    // Scroll the ongoing class to the front, leaving a sliver
                    // of the one before it so the row reads as "you are here"
                    // rather than as the start of the day. Nothing precedes
                    // index 0, so it needs no offset — and no ongoing class
                    // means no scrolling at all, leaving the day from its
                    // beginning.
                    val density = LocalDensity.current
                    LaunchedEffect(firstOngoingIndex) {
                        if (firstOngoingIndex <= 0) return@LaunchedEffect
                        val target = with(density) {
                            carouselScrollTarget(
                                isOngoing = todayCourses.map { it.courseNo in ongoingByNo },
                                firstOngoingIndex = firstOngoingIndex,
                            ).roundToPx()
                        }
                        // maxValue is 0 until the row has been measured, and
                        // animateScrollTo would clamp the target to it.
                        snapshotFlow { rowScroll.maxValue }.first { it > 0 }
                        rowScroll.animateScrollTo(target)
                    }

                    Row(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .horizontalScroll(rowScroll)
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = CAROUSEL_EDGE_INSET)
                    ) {
                        todayCourses.forEachIndexed { index, course ->
                            val ongoing = ongoingByNo[course.courseNo]
                            if (ongoing != null) {
                                CurrentClassCard(
                                    course = ongoing.course,
                                    blockStartMinute = ongoing.startMinute,
                                    blockEndMinute = ongoing.endMinute,
                                    currentMinute = currentMinute,
                                    hasAssignment = course.courseNo in courseNosWithAssignments,
                                    weekday = ongoing.weekday,
                                    onClick = {
                                        viewModel.selectCourse(
                                            ongoing.course,
                                            ongoing.weekday,
                                            ongoing.firstPeriodId
                                        )
                                    },
                                    modifier = Modifier.fillMaxHeight()
                                )
                            } else {
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
                                    hasAssignment = course.courseNo in courseNosWithAssignments,
                                    isFinished = viewModel.isCourseFinishedToday(course),
                                    weekday = dayIndex,
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
                            }
                            if (index < todayCourses.lastIndex) {
                                Spacer(Modifier.width(CourseCardGap))
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
                        options = availableSemesters,
                        labelFor = viewModel::displayLabel,
                        onPick = { viewModel.setSemester(it) }
                    )
                    Spacer(Modifier.weight(1f))
                    // "B11315000 · 20 credits" — the id leads so a shared
                    // screenshot shows whose timetable it is. Both halves keep
                    // the one secondary tint; the id is dropped rather than
                    // left as a stray separator when there is none.
                    val credits = stringResource(
                        R.string.class_table_total_credits_value,
                        viewModel.totalCredits
                    )
                    Text(
                        text = viewModel.studentId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "$it · $credits" }
                            ?: credits,
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
                        courseNosWithAssignments = courseNosWithAssignments,
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
            classroom = viewModel.selectedCourseClassroom,
            timeRange = viewModel.selectedCourseTimeRange,
            assignments = allAssignments.filter { it.courseNo == course.courseNo && !it.isCompleted },
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
        TigerDuckDialog(
            onDismissRequest = { courseToRename = null },
            title = stringResource(R.string.class_table_rename_title),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                viewModel.setCustomCourseName(course.courseNo, renameText)
                courseToRename = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { courseToRename = null },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
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
            weekday = target.weekday,
            onPick = { picked ->
                viewModel.selectCourse(picked, target.weekday, target.periodId)
                conflictPicker = null
            },
            onDismiss = { conflictPicker = null },
        )
    }

    tripleConflictError?.let { err ->
        val dayLabel = weekdayShortLabels.getOrElse(err.weekday) { err.weekday.toString() }
        TigerDuckDialog(
            onDismissRequest = { tripleConflictError = null },
            title = stringResource(R.string.class_table_conflict_add_failed_title),
            message = stringResource(
                R.string.class_table_conflict_add_failed_message,
                err.newCourseName,
                dayLabel,
                err.periodId,
                err.existingA.displayName,
                err.existingB.displayName,
            ),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { tripleConflictError = null },
        )
    }

    if (showResetConfirm) {
        TigerDuckDialog(
            onDismissRequest = { showResetConfirm = false },
            // Names the term: the reset is scoped to the semester on screen
            // now, and an unqualified "Reset class table" reads as all of them.
            title = stringResource(
                R.string.class_table_reset_title_with_semester,
                viewModel.displayLabel(selectedSemester),
            ),
            message = stringResource(R.string.class_table_reset_message),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                showResetConfirm = false
                viewModel.resetCourses()
            },
            dismissText = stringResource(R.string.action_cancel),
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
