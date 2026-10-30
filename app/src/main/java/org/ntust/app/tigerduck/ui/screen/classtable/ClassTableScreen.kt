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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.ntust.app.tigerduck.ui.component.CurrentClassCardWidth
import org.ntust.app.tigerduck.ui.component.CourseCardWidth
import org.ntust.app.tigerduck.ui.component.CourseCardGap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShare
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.rememberDetentSheetState
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableSwitcherEntry
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableSwitcherMenuItems
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableShareSheet
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.ColorPickerSheet
import org.ntust.app.tigerduck.ui.component.colorPickerOffersDarkPalette
import org.ntust.app.tigerduck.ui.component.ConflictCoursePickerSheet
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.component.CourseCard
import org.ntust.app.tigerduck.ui.component.CurrentClassCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.PageLeftEffect
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.courseColorPaletteDark
import org.ntust.app.tigerduck.util.formatCredits

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
    /** Opens the class table import, which the navigation host presents over every page. */
    onRequestImport: () -> Unit = {},
) {
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    // What the page draws: the user's own class table, or someone else's, read-only.
    val shown by viewModel.shownTable.collectAsStateWithLifecycle()
    val importedTables by viewModel.importedTables.collectAsStateWithLifecycle()
    val isRefreshingShownPerson by viewModel.isRefreshingShownPerson.collectAsStateWithLifecycle()
    val shownPerson = shown.person
    val shownCourses = shown.courses
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val hasSettled by viewModel.hasSettled.collectAsStateWithLifecycle()
    val schoolDataSyncedAtMs by viewModel.schoolDataSyncedAtMs.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val currentMinute by viewModel.currentMinute.collectAsStateWithLifecycle()
    val thisWeek by viewModel.thisWeek.collectAsStateWithLifecycle()
    // Collected as snapshot state (rather than reading the ViewModel's raw
    // StateFlow.value) so the badge icons recompose when the assignments
    // fetch completes after the courses fetch — a raw read registers no
    // Compose dependency, leaving badges stale until the next courses update.
    val outstandingAssignments by viewModel.outstandingAssignments.collectAsStateWithLifecycle()
    val courseNosWithAssignments by viewModel.courseNosWithAssignments.collectAsStateWithLifecycle()
    val courseNosIgnoringAssignments by
        viewModel.courseNosIgnoringAssignments.collectAsStateWithLifecycle()
    val canIgnoreAssignments by viewModel.canIgnoreAssignments.collectAsStateWithLifecycle()
    // Hoisted alongside the other top-level subscriptions: a second
    // collectAsStateWithLifecycle for the same flow inside a conditional
    // body invites readers from the wrong scope and is fragile if the
    // condition changes.
    val selectedSemester by viewModel.currentSemester.collectAsStateWithLifecycle()
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
    val alwaysShowAllPeriods by viewModel.alwaysShowAllPeriods.collectAsStateWithLifecycle()
    val activePeriods = remember(shownCourses, alwaysShowAllPeriods) {
        ClassTableCellLayout.activePeriods(shownCourses, showAllPeriods = alwaysShowAllPeriods)
    }
    val activeWeekdays = remember(shownCourses) { ClassTableCellLayout.activeWeekdays(shownCourses) }
    val showClassroomInClassTable by viewModel.showClassroomInClassTable.collectAsStateWithLifecycle()
    val pinTodayCoursesOnClassTable by viewModel.pinTodayCoursesOnClassTable.collectAsStateWithLifecycle()
    val outlineRequiredCourses by viewModel.outlineRequiredCourses.collectAsStateWithLifecycle()
    // Someone else's class table is kept on the device, so no sign-in stands in front of it.
    val showsTable = isLoggedIn || shownPerson != null
    val hasGrid = showsTable && activePeriods.isNotEmpty() && activeWeekdays.isNotEmpty() && shownCourses.isNotEmpty()
    // Whose class table it is, for the credits label and the exported image: the student ID,
    // or the nickname of someone else's.
    val owner = shownPerson?.nickname ?: viewModel.studentId
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAddCourse by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // The term whose reset the backend could not take.
    var resetFailedSemester by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<ClassTableShare?>(null) }
    val shareTerms by viewModel.shareTerms.collectAsStateWithLifecycle()
    var courseToRename by remember { mutableStateOf<Course?>(null) }
    var courseToRecolor by remember { mutableStateOf<Course?>(null) }
    var conflictPicker by remember { mutableStateOf<ConflictPickerTarget?>(null) }
    var tripleConflictError by remember {
        mutableStateOf<TripleConflictError?>(
            null
        )
    }
    val scrollState = rememberScrollState()
    // Read as the grid is placed, not drawn from, so neither is snapshot state.
    val scrolledContent = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val lastRowTops = remember { arrayOfNulls<ClassTableRowTops>(1) }
    val lastScroll = remember { intArrayOf(0) }
    var renamingPerson by remember { mutableStateOf<ImportedPerson?>(null) }
    var nicknameDraft by remember { mutableStateOf("") }
    var deletingPerson by remember { mutableStateOf<ImportedPerson?>(null) }
    var importedTableProblem by remember { mutableStateOf<ImportedTableProblem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val classTableTitle = stringResource(R.string.feature_class_table)
    val exportTitle = stringResource(R.string.class_table_export_screenshot)
    val exportFailedMessage = stringResource(R.string.class_table_export_failed)
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

    LaunchedEffect(viewModel) { 
        viewModel.load()
        // Each time the page enters composition: a return to it from
        // another page fetches if its RefreshPolicy asks for that.
        viewModel.onPageShown()
    }
    PageLeftEffect(viewModel::onPageLeft)
    // The page's own resume, not the activity's: delivered on coming to the
    // page and on each return from the background with it showing. The
    // page's lifecycle answers, right before the launch, whether it still
    // is; the back stack entry's, so another page on top ends it.
    val pageLifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onPageResumed { pageLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }
    // Paused with the activity — a background trip, a system prompt: the
    // moment it offered is withdrawn.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPagePaused() }
    LaunchedEffect(viewModel) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(errorNetworkUnavailable)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.tripleConflictEvent.collect { tripleConflictError = it }
    }
    LaunchedEffect(viewModel) {
        viewModel.importedTableProblems.collect { importedTableProblem = it }
    }
    LaunchedEffect(viewModel) {
        viewModel.resetFailedEvent.collect { resetFailedSemester = it }
    }
    // The tab's menu, a double tap or a deletion can switch class tables while one of the
    // user's own edits is open, and it must not save into someone else's.
    LaunchedEffect(shownPerson?.id) {
        if (shownPerson == null) return@LaunchedEffect
        showAddCourse = false
        showResetConfirm = false
        courseToRename = null
        courseToRecolor = null
        conflictPicker = null
        sharing = null
    }


    Box(modifier = Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = if (shownPerson != null) isRefreshingShownPerson else isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = refreshingMessage,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // The scrolled content, which the grid's rows are measured in.
                    .onGloballyPositioned { scrolledContent[0] = it }
            ) {
                PageHeader(title = stringResource(R.string.feature_class_table)) {
                    SyncStatusDot(
                        servers = listOf(
                            ServerKind.MOODLE,
                            ServerKind.COURSE_SELECTION,
                            ServerKind.BACKEND,
                        ),
                        isLoading = isLoading,
                        syncedAtMs = schoolDataSyncedAtMs,
                    )
                    // Everything that acts on the timetable, behind one ⋮ —
                    // the same shape as a mail message's actions. A third
                    // glyph would have made a row of unlabelled icons, and
                    // export is not something an icon alone can say.
                    Box {
                        IconButton(
                            onClick = {
                                showMoreMenu = true
                                viewModel.refreshShareTerms()
                            },
                            enabled = showsTable
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.class_table_more_actions),
                                tint = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (showsTable) ContentAlpha.SECONDARY else ContentAlpha.DISABLED
                                )
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (shownPerson != null) {
                                // Someone else's class table is read-only: export and import
                                // as on the user's own, then what applies to the person.
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.class_table_export_screenshot)) },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    onClick = { showMoreMenu = false; exporting = true },
                                    enabled = hasGrid && !exporting,
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.class_table_import)) },
                                    leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                                    onClick = { showMoreMenu = false; onRequestImport() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.class_table_rename_title)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        nicknameDraft = shownPerson.nickname
                                        renamingPerson = shownPerson
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.class_table_delete_user),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = { showMoreMenu = false; deletingPerson = shownPerson },
                                )
                                return@DropdownMenu
                            }
                            OwnClassTableMenuItems(
                                onAddCourse = { showMoreMenu = false; showAddCourse = true },
                                // Held off with an export, whose chooser would
                                // open behind the sheet.
                                shareEnabled = !exporting && shareTerms.isNotEmpty() &&
                                    ClassTableShare.canName(viewModel.studentId),
                                onShare = {
                                    showMoreMenu = false
                                    val id = viewModel.studentId
                                    if (id != null && shareTerms.isNotEmpty()) {
                                        sharing = ClassTableShare(id, shareTerms)
                                    }
                                },
                                onImport = { showMoreMenu = false; onRequestImport() },
                                // Only exportable while the grid below is
                                // drawn; anything else would export a header
                                // over nothing. Also off while one is in
                                // flight.
                                exportEnabled = hasGrid && !exporting,
                                onExport = { showMoreMenu = false; exporting = true },
                                onReset = { showMoreMenu = false; showResetConfirm = true },
                            )
                        }
                    }
                }
                org.ntust.app.tigerduck.ui.screen.signin.AutoSignInFailedBanner(Modifier.padding(bottom = 8.dp))

                if (!showsTable) {
                    org.ntust.app.tigerduck.ui.component.EmptyStateView(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.common_not_signed_in),
                        message = stringResource(R.string.common_sign_in_required_feature),
                        actionLabel = stringResource(R.string.onboarding_sign_in_button),
                        onAction = onOpenSignInSettings,
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
                // The user's own day, which would read as the other person's on theirs,
                // and only where Settings pins it here: Home already lists it, and
                // without it the grid starts at the top of the page.
                if (pinTodayCoursesOnClassTable && shownPerson == null && todayCourses.isNotEmpty()) {
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
                                    periods = ongoing.periods,
                                    onClick = {
                                        viewModel.selectCourse(
                                            ongoing.course,
                                            ongoing.weekday,
                                            ongoing.firstPeriodId,
                                            fromLiveTerm = true,
                                            periods = ongoing.periods,
                                            date = AppClock.localDateTime().toLocalDate(),
                                        )
                                    },
                                    modifier = Modifier.fillMaxHeight()
                                )
                            } else {
                                // The block the card stands for: the next one,
                                // or the last once the day's are over, so a
                                // course meeting twice shows its afternoon
                                // times and room after the morning block.
                                val block = remember(course, dayIndex, currentMinute) {
                                    ClassTableSelection.carouselBlock(course, dayIndex, currentMinute)
                                }
                                val timeRange = remember(block) {
                                    if (!block.isNullOrEmpty()) {
                                        val first = AppConstants.PeriodTimes.mapping[block.first()]
                                        val last = AppConstants.PeriodTimes.mapping[block.last()]
                                        if (first != null && last != null) "${first.first}-${last.second}" else null
                                    } else null
                                }
                                CourseCard(
                                    course = course,
                                    timeRange = timeRange,
                                    hasAssignment = course.courseNo in courseNosWithAssignments,
                                    isFinished = viewModel.isCourseFinishedToday(course),
                                    weekday = dayIndex,
                                    periods = block,
                                    onClick = {
                                        viewModel.selectCourse(
                                            course,
                                            dayIndex,
                                            block?.first() ?: "",
                                            fromLiveTerm = true,
                                            periods = block,
                                            date = AppClock.localDateTime().toLocalDate(),
                                        )
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
                        current = shown.semester,
                        options = shown.semesters,
                        labelFor = viewModel::displayLabel,
                        onPick = { viewModel.setSemester(it) }
                    )
                    Spacer(Modifier.weight(1f))
                    // "B11315000 · 20 credits", or the nickname on someone
                    // else's: whose timetable it is leads, so a shared
                    // screenshot shows it. The owner is dropped rather than
                    // left as a stray separator when there is none.
                    val credits = stringResource(
                        R.string.class_table_total_credits_value,
                        shownCourses.fold(0f) { acc, c -> acc + c.credits }.formatCredits()
                    )
                    ClassTableOwnerLabel(
                        text = owner?.takeIf { it.isNotBlank() }?.let { "$it · $credits" } ?: credits,
                        tables = importedTables,
                        userName = viewModel.studentId?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.common_not_signed_in),
                        onImport = onRequestImport,
                        onShow = viewModel::showTable,
                    )
                }

                // Under the picker, which stays to switch to a term that has courses.
                if (shown.showsNoCourses(settled = hasSettled, isLoading = isLoading)) {
                    org.ntust.app.tigerduck.ui.component.EmptyStateView(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = stringResource(R.string.home_time_slider_no_courses),
                        // A shared term always has course codes, so someone else's is
                        // empty only when none could be looked up, and it has no Add course.
                        message = stringResource(
                            if (shownPerson != null) {
                                R.string.class_table_import_lookup_failed
                            } else {
                                R.string.class_table_empty_message
                            }
                        ),
                    )
                }

                // Timetable
                if (!hasGrid) lastRowTops[0] = null
                if (hasGrid) {
                    TimetableGrid(
                        courses = shownCourses,
                        showRoomHints = showClassroomInClassTable,
                        weekdays = activeWeekdays,
                        periods = activePeriods,
                        // The user's assignments, so none on someone else's
                        // class table, even for a course both take.
                        courseNosWithAssignments = if (shownPerson == null) courseNosWithAssignments else emptySet(),
                        courseNosIgnoringAssignments =
                            if (shownPerson == null) courseNosIgnoringAssignments else emptySet(),
                        outlineRequiredCourses = outlineRequiredCourses,
                        onSelect = { course, weekday, periodId ->
                            viewModel.selectCourse(course, weekday, periodId, date = thisWeek?.date(weekday))
                        },
                        editable = shownPerson == null,
                        thisWeek = thisWeek,
                        nowMinute = currentMinute,
                        onRowsPlaced = { rows, rowHeightPx ->
                            val content = scrolledContent[0]
                            if (content != null && content.isAttached && rows.isAttached) {
                                val top = content.localPositionOf(rows, Offset.Zero).y
                                val now = ClassTableRowTops(
                                    shown.switches,
                                    activePeriods.withIndex().associate { (i, p) -> p.id to top + i * rowHeightPx },
                                )
                                val before = lastRowTops[0]
                                val scrollBefore = lastScroll[0]
                                lastRowTops[0] = now
                                lastScroll[0] = scrollState.value
                                // Scrolls to where the switch moved the rows, so two people's
                                // classes line up on screen. From the scroll they were measured
                                // at: a shorter page has already pulled the scroll back by now.
                                // A page at its top stays there.
                                if (before != null && before.switches != now.switches && scrollBefore > 0) {
                                    val shift = ClassTableRowTops.shift(before, now, scrollBefore.toFloat())
                                    val target = (scrollBefore + shift).coerceIn(0f, scrollState.maxValue.toFloat())
                                    scrollState.dispatchRawDelta(target - scrollState.value)
                                    lastScroll[0] = scrollState.value
                                }
                            }
                        },
                        onRename = { course -> courseToRename = course },
                        onDelete = { course ->
                            viewModel.deleteCourse(course.courseNo)
                        },
                        onPickColor = { course ->
                            courseToRecolor = course
                        },
                        onToggleIgnoreAssignments = if (canIgnoreAssignments) {
                            viewModel::toggleIgnoreAssignments
                        } else {
                            null
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

        if (exporting) {
            val semesterLabel = viewModel.displayLabel(shown.semester)
            // A nickname has no length limit, and a file name past 255 bytes cannot be written.
            val studentId = owner?.take(40)
            OffscreenCapture(
                onCaptured = { bitmap ->
                    val file = writeClassTableImage(
                        context,
                        bitmap,
                        classTableExportFileName(classTableTitle, semesterLabel, studentId),
                    )
                    shareClassTableImage(context, file, exportTitle)
                    exporting = false
                },
                onFailed = {
                    exporting = false
                    // In the screen's scope: the capture's own ends with the
                    // export, and the snackbar outlives it.
                    scope.launch { snackbarHostState.showSnackbar(exportFailedMessage) }
                },
            ) {
                ClassTableExportCard(
                    courses = shownCourses,
                    semesterLabel = semesterLabel,
                    studentId = studentId,
                    showRoomHints = showClassroomInClassTable,
                    weekdays = activeWeekdays,
                    periods = activePeriods,
                    outlineRequiredCourses = outlineRequiredCourses,
                )
            }
        }
    } // Box

    courseToRename?.let { course ->
        CourseRenameDialog(viewModel = viewModel, course = course, onDismiss = { courseToRename = null })
    }

    courseToRecolor?.let { course ->
        val darkPalette = LocalVisualStylePolicy.current.colorPickerOffersDarkPalette(TigerDuckTheme.isDarkMode)
        val displayPalette = if (darkPalette) courseColorPaletteDark else courseColorPalette
        // Each course's color as the palette above has it.
        val shown = if (darkPalette) TigerDuckTheme::courseColor else TigerDuckTheme::courseColorVibrant
        val currentColor = shown(course.courseNo)
        val usedByOthers = remember(courses, course.courseNo, darkPalette) {
            courses
                .asSequence()
                .filter { it.courseNo != course.courseNo }
                .map { shown(it.courseNo).toArgb() or 0xFF000000.toInt() }
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
            periodId = target.periodId,
            onPick = { picked ->
                viewModel.selectCourse(
                    picked, target.weekday, target.periodId, date = viewModel.thisWeek.value?.date(target.weekday),
                )
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

    // Nothing was reset: the term is as it was. iOS says the same.
    resetFailedSemester?.let { semester ->
        TigerDuckDialog(
            onDismissRequest = { resetFailedSemester = null },
            title = stringResource(
                R.string.class_table_reset_title_with_semester,
                viewModel.displayLabel(semester),
            ),
            message = stringResource(R.string.error_network_unavailable),
            confirmText = stringResource(R.string.action_confirm),
        )
    }

    renamingPerson?.let { person ->
        TigerDuckDialog(
            onDismissRequest = { renamingPerson = null },
            title = stringResource(R.string.class_table_rename_title),
            confirmText = stringResource(R.string.action_save),
            // The store keeps no blank nickname.
            confirmEnabled = nicknameDraft.isNotBlank(),
            onConfirm = {
                viewModel.renamePerson(person.id, nicknameDraft)
                renamingPerson = null
            },
            dismissText = stringResource(R.string.action_cancel),
        ) {
            OutlinedTextField(
                value = nicknameDraft,
                onValueChange = { nicknameDraft = it },
                label = { Text(stringResource(R.string.class_table_nickname)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    deletingPerson?.let { person ->
        TigerDuckDialog(
            onDismissRequest = { deletingPerson = null },
            title = stringResource(R.string.class_table_delete_user_confirm, person.nickname),
            confirmText = stringResource(R.string.class_table_delete),
            confirmColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            onConfirm = {
                viewModel.deletePerson(person.id)
                deletingPerson = null
            },
            dismissText = stringResource(R.string.action_cancel),
        )
    }

    importedTableProblem?.let { problem ->
        TigerDuckDialog(
            onDismissRequest = { importedTableProblem = null },
            title = stringResource(
                when (problem) {
                    ImportedTableProblem.LOOKUP_FAILED -> R.string.class_table_import_lookup_failed
                    ImportedTableProblem.SAVE_FAILED -> R.string.bulletin_save_failed_title
                }
            ),
            confirmText = stringResource(R.string.action_confirm),
        )
    }

    sharing?.let { share ->
        ClassTableShareSheet(
            share = share,
            labelFor = viewModel::displayLabel,
            onDismiss = { sharing = null },
            onScan = onRequestImport,
        )
    }
    // A sign-out closes the sheet: the code names the account that left.
    LaunchedEffect(isLoggedIn) { if (!isLoggedIn) sharing = null }

    if (showAddCourse) {
        val addCourseSheetState = rememberDetentSheetState()
        DetentSheet(
            onDismissed = { showAddCourse = false },
            state = addCourseSheetState,
            halfHeight = true,
        ) {
            val currentSemester by viewModel.currentSemester.collectAsStateWithLifecycle()
            AddCourseSheet(
                semester = currentSemester,
                existingCourseNos = viewModel.existingCourseNos,
                courseService = viewModel.courseService,
                sheetState = addCourseSheetState,
                onAdd = { viewModel.addCourse(it) },
                onRemove = { viewModel.removeAddedCourse(it) },
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

/**
 * "B11315000 · 20 credits". Plain text until someone's class table has been imported, then the
 * class table switcher's menu: the tab's long press reaches no class table under More or with
 * TalkBack, so this does.
 */
@Composable
private fun ClassTableOwnerLabel(
    text: String,
    tables: ImportedClassTables,
    userName: String,
    onImport: () -> Unit,
    onShow: (personId: String?) -> Unit,
) {
    if (!tables.hasImportedClassTables) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val switchLabel = stringResource(R.string.a11y_class_table_switch)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .semantics(mergeDescendants = true) {
                    role = Role.DropdownList
                    contentDescription = switchLabel
                    stateDescription = text
                }
                .clickable { expanded = true }
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp),
        ) {
            ClassTableSwitcherMenuItems(ClassTableSwitcherEntry.sections(tables, userName)) { entry ->
                expanded = false
                when (entry) {
                    ClassTableSwitcherEntry.ImportClassTable -> onImport()
                    is ClassTableSwitcherEntry.Person -> onShow(entry.id)
                    is ClassTableSwitcherEntry.User -> onShow(null)
                }
            }
        }
    }
}

