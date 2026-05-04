package org.ntust.app.tigerduck.ui.screen.home

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.*
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appState: AppState,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val allCourses by viewModel.allCourses.collectAsStateWithLifecycle()
    val upcomingAssignments by viewModel.upcomingAssignments.collectAsStateWithLifecycle()
    val assignmentFilter by viewModel.assignmentFilter.collectAsStateWithLifecycle()
    val ignoredAssignmentIds by viewModel.ignoredAssignmentIds.collectAsStateWithLifecycle()
    val markedCompletedIds by viewModel.markedCompletedIds.collectAsStateWithLifecycle()
    val ignoredTabPinned by viewModel.ignoredTabPinned.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val initialLoadComplete by viewModel.initialLoadComplete.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val selectedCourse by viewModel.selectedCourse.collectAsStateWithLifecycle()
    // 翹課 feature disabled — kept for potential re-enable.
    // val skippedDates by viewModel.skippedDates.collectAsStateWithLifecycle()
    var showComingSoon by remember { mutableStateOf(false) }
    var showCheckmark by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    var isEditing by remember { mutableStateOf(false) }
    var showAddSectionDialog by remember { mutableStateOf(false) }

    // Intercept the system back gesture while editing — exit edit mode
    // instead of navigating away. This matches Android-native expectations
    // for any screen with a distinct modal edit state.
    BackHandler(enabled = isEditing) {
        isEditing = false
    }

    // Per-section measured height — needed for the drag-and-swap threshold
    // math. Position on screen is *not* tracked because layout changes during
    // reorder would pollute it; height is stable enough per section.
    val sectionHeights = remember { mutableStateMapOf<String, Float>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // If edit mode is cancelled (back gesture, etc.) mid-drag, clear drag state.
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            draggingId = null
            dragOffsetY = 0f
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }

    // When the Home screen leaves the foreground (tab switch, background),
    // reset the filter away from 已忽略 if it ended up empty. This makes the
    // "stay pinned on IGNORED tab while the user might want to undo" behavior
    // only apply *during* the current visit.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onHomePaused()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Key on viewModel so a fresh collector is launched on each remount;
    // collecting on Unit means events emitted while the screen is in the back
    // stack stay attached to a stale collector and can be lost.
    LaunchedEffect(viewModel) {
        viewModel.syncCompleteEvent.collect {
            showCheckmark = true
            delay(2000)
            showCheckmark = false
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(resources.getString(R.string.error_network_unavailable))
        }
    }

    var pullProgress by remember { mutableFloatStateOf(0f) }
    // Stable lambda reference: a fresh `viewModel::hasUnfinishedAssignment`
    // bound-method allocation per recomposition would defeat skipping for
    // every HomeSectionContent below.
    val hasUnfinishedAssignment = remember(viewModel) {
        { id: String -> viewModel.hasUnfinishedAssignment(id) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            onDragProgress = { pullProgress = it },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = !isEditing,
            ) {
                item {
                    PageHeader(title = greetingText()) {
                        if (isEditing) {
                            IconButton(onClick = { showAddSectionDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_section_title))
                            }
                            TextButton(onClick = { isEditing = false }) {
                                Text(stringResource(R.string.action_done), fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            SyncIndicator(
                                isLoading = isLoading,
                                showCheckmark = showCheckmark,
                                dragProgress = pullProgress,
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = sections,
                    key = { _, s -> s.id },
                ) { index, section ->
                    ReorderableSection(
                        section = section,
                        isEditing = isEditing,
                        isDragging = draggingId == section.id,
                        dragOffsetY = if (draggingId == section.id) dragOffsetY else 0f,
                        onStartEditing = { isEditing = true },
                        onDragStart = {
                            draggingId = section.id
                            dragOffsetY = 0f
                        },
                        onDrag = { delta ->
                            dragOffsetY += delta
                            val fromIdx = sections.indexOfFirst { it.id == section.id }
                            if (fromIdx < 0) return@ReorderableSection

                            // Threshold-based swap: when the drag has crossed half of
                            // the neighbor's height, swap in the data model and
                            // compensate `dragOffsetY` so the card stays visually
                            // under the user's finger (no snap-back).
                            if (dragOffsetY > 0 && fromIdx < sections.lastIndex) {
                                val nextH = sectionHeights[sections[fromIdx + 1].id] ?: return@ReorderableSection
                                if (dragOffsetY > nextH / 2f) {
                                    viewModel.moveSections(fromIdx, fromIdx + 1)
                                    dragOffsetY -= nextH
                                }
                            } else if (dragOffsetY < 0 && fromIdx > 0) {
                                val prevH = sectionHeights[sections[fromIdx - 1].id] ?: return@ReorderableSection
                                if (dragOffsetY < -prevH / 2f) {
                                    viewModel.moveSections(fromIdx, fromIdx - 1)
                                    dragOffsetY += prevH
                                }
                            }
                        },
                        onDragEnd = {
                            draggingId = null
                            dragOffsetY = 0f
                        },
                        onMeasured = { height ->
                            sectionHeights[section.id] = height
                        },
                        onRemove = { viewModel.removeSection(section.id) },
                    ) {
                        HomeSectionContent(
                            section = section,
                            allCourses = allCourses,
                            upcomingAssignments = upcomingAssignments,
                            assignmentFilter = assignmentFilter,
                            showIgnoredTab = ignoredAssignmentIds.isNotEmpty() || ignoredTabPinned,
                            ignoredAssignmentIds = ignoredAssignmentIds,
                            markedCompletedIds = markedCompletedIds,
                            isLoggedIn = isLoggedIn,
                            isLoading = isLoading,
                            initialLoadComplete = initialLoadComplete,
                            hasUnfinishedAssignment = hasUnfinishedAssignment,
                            showAbsoluteTime = appState.showAbsoluteAssignmentTime,
                            invertDirection = appState.invertSliderDirection,
                            onCourseClick = {
                                if (!isEditing) viewModel.selectCourse(it)
                            },
                            onAssignmentClick = {
                                if (!isEditing) openAssignmentInMoodle(context, it)
                            },
                            onToggleIgnore = {
                                if (!isEditing) viewModel.toggleIgnore(it)
                            },
                            onMarkCompleted = {
                                if (!isEditing) viewModel.toggleMarkCompleted(it)
                            },
                            onSelectFilter = { viewModel.setAssignmentFilter(it) },
                            // 翹課 feature disabled — replaced by 已忽略 homework flow.
                            // onSkipCourse = { course, date ->
                            //     if (!isEditing) viewModel.toggleSkip(course, date)
                            // },
                            onWidgetClick = { if (!isEditing) showComingSoon = true }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box

    if (showAddSectionDialog) {
        AddSectionDialog(
            existingSections = sections,
            onAddBuiltin = { type ->
                viewModel.addSection(type, resources.getString(type.defaultTitleRes))
                showAddSectionDialog = false
            },
            onAddCustom = { title ->
                viewModel.addSection(HomeSection.HomeSectionType.CUSTOM, title)
                showAddSectionDialog = false
            },
            onDismiss = { showAddSectionDialog = false },
        )
    }

    if (showComingSoon) {
        ComingSoonDialog(onDismiss = { showComingSoon = false })
    }

    selectedCourse?.let { course ->
        // Cache per courseNo so re-running the linear filter every parent
        // recomposition (any state tick while the dialog is open) goes away.
        val dialogAssignments = remember(course.courseNo, upcomingAssignments) {
            viewModel.assignmentsFor(course.courseNo)
        }
        CourseDetailDialog(
            course = course,
            assignments = dialogAssignments,
            onDismiss = { viewModel.selectCourse(null) }
        )
    }
}

@Composable
private fun ReorderableSection(
    section: HomeSection,
    isEditing: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    onStartEditing: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onMeasured: (height: Float) -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Material-style elevation ramp for the dragging card — no wiggle.
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 12f else 0f,
        label = "drag-elevation",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onMeasured(coords.size.height.toFloat())
            }
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .then(
                if (isDragging) Modifier.shadow(elevation.dp, RoundedCornerShape(18.dp))
                else Modifier
            )
            .then(
                // Only long-press-to-enter-edit-mode lives here. Reordering
                // drags come from the dedicated handle below, so tap events
                // on child cards are never stolen by this gesture detector.
                if (!isEditing) {
                    Modifier.pointerInput(section.id) {
                        detectTapGestures(onLongPress = { onStartEditing() })
                    }
                } else {
                    Modifier
                }
            )
    ) {
        content()

        if (isEditing) {
            // Top-right row: big touch-friendly drag handle + delete.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 20.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Drag handle — 44dp hit target, starts reorder on first
                // touch-and-move (no long-press required).
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(section.id) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, drag ->
                                    change.consume()
                                    onDrag(drag.y)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = stringResource(R.string.tab_editor_drag_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                // Delete
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable(enabled = !isDragging) { onRemove() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionContent(
    section: HomeSection,
    allCourses: List<Course>,
    upcomingAssignments: List<Assignment>,
    assignmentFilter: AssignmentFilter,
    showIgnoredTab: Boolean,
    ignoredAssignmentIds: Set<String>,
    markedCompletedIds: Set<String>,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    initialLoadComplete: Boolean,
    hasUnfinishedAssignment: (String) -> Boolean,
    showAbsoluteTime: Boolean,
    invertDirection: Boolean,
    onCourseClick: (Course) -> Unit,
    onAssignmentClick: (Assignment) -> Unit,
    onToggleIgnore: (Assignment) -> Unit,
    onMarkCompleted: (Assignment) -> Unit,
    onSelectFilter: (AssignmentFilter) -> Unit,
    onWidgetClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (section.type) {
            HomeSection.HomeSectionType.TODAY_COURSES -> {
                TimeSliderSection(
                    courses = allCourses,
                    invertDirection = invertDirection,
                    isLoggedIn = isLoggedIn,
                    initialLoadComplete = initialLoadComplete,
                    onSelectCourse = onCourseClick
                )
            }

            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> {
                SectionHeader(title = if (section.type == HomeSection.HomeSectionType.CUSTOM) section.title else stringResource(section.type.defaultTitleRes))
                if (isLoggedIn) {
                    AssignmentFilterTabs(
                        selected = assignmentFilter,
                        enabled = true,
                        showIgnoredTab = showIgnoredTab,
                        onSelect = onSelectFilter,
                    )
                }
                if (upcomingAssignments.isEmpty()) {
                    AssignmentsEmptyState(
                        isLoggedIn = isLoggedIn,
                        isLoading = isLoading || (!initialLoadComplete && isLoggedIn),
                        filter = assignmentFilter,
                    )
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        upcomingAssignments.forEachIndexed { index, assignment ->
                            // SwipeableAssignmentRow keeps an Animatable keyed
                            // by assignmentId; without `key()` here the parent
                            // forEach has no item identity, so removing or
                            // ignoring an assignment would rebind the next
                            // row to the prior Animatable and leak swipe state.
                            key(assignment.assignmentId) {
                                val isMarkedCompleted = assignment.assignmentId in markedCompletedIds
                                SwipeableAssignmentRow(
                                    assignment = assignment,
                                    isIgnored = assignment.assignmentId in ignoredAssignmentIds,
                                    isMarkedCompleted = isMarkedCompleted,
                                    showAbsoluteTime = showAbsoluteTime,
                                    onClick = { onAssignmentClick(assignment) },
                                    onToggleIgnore = { onToggleIgnore(assignment) },
                                    onMarkCompleted = { onMarkCompleted(assignment) },
                                )
                                if (index < upcomingAssignments.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            HomeSection.HomeSectionType.QUICK_WIDGETS,
            HomeSection.HomeSectionType.CUSTOM -> {
                SectionHeader(title = if (section.type == HomeSection.HomeSectionType.CUSTOM) section.title else stringResource(section.type.defaultTitleRes))
                if (section.widgets.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(section.widgets, key = { it.id }) { widget ->
                            Card(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clickable { onWidgetClick() },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
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
                                        text = stringResource(widget.feature.displayNameRes),
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
                Text(stringResource(R.string.course_instructor_value, course.instructor), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.course_classroom_value, course.classroom), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.course_credits_value, course.credits), style = MaterialTheme.typography.bodyMedium)
                if (assignments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.home_pending_assignments), style = MaterialTheme.typography.titleSmall)
                    assignments.forEach { a ->
                        Text("• ${a.title}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun greetingText(): String {
    val hour = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> stringResource(R.string.greeting_very_early)
        hour < 12 -> stringResource(R.string.greeting_morning)
        hour < 18 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }
}

private fun openAssignmentInMoodle(context: Context, assignment: Assignment) {
    val targets = listOfNotNull(assignment.moodleDeepLink, assignment.moodleUrl)
    for (target in targets) {
        val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (opened) return
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignmentFilterTabs(
    selected: AssignmentFilter,
    enabled: Boolean,
    showIgnoredTab: Boolean,
    onSelect: (AssignmentFilter) -> Unit,
) {
    // Hide 已忽略 when the user has nothing ignored — unless they're already
    // on that tab, so clearing the last item doesn't yank the section out
    // from under them mid-interaction.
    val options = if (showIgnoredTab) AssignmentFilter.entries
                  else AssignmentFilter.entries.filter { it != AssignmentFilter.IGNORED }
    val segmentColors = tigerDuckSegmentedButtonColors()
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = enabled && option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = segmentColors,
            ) {
                Text(stringResource(option.displayNameRes), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun tigerDuckSegmentedButtonColors(): SegmentedButtonColors {
    val primary = MaterialTheme.colorScheme.primary
    // Blend toward white in dark mode so the tint reads as a *lighter* pastel
    // accent. A raw primary tint composites darker than the dark card surface.
    val activeContainer = if (TigerDuckTheme.isDarkMode) {
        lerp(primary, Color.White, 0.7f).copy(alpha = 0.22f)
    } else {
        primary.copy(alpha = 0.08f)
    }
    return SegmentedButtonDefaults.colors(
        activeContainerColor = activeContainer,
        activeContentColor = primary,
        activeBorderColor = primary,
    )
}

@Composable
private fun AssignmentsEmptyState(
    isLoggedIn: Boolean,
    isLoading: Boolean,
    filter: AssignmentFilter,
) {
    if (!isLoggedIn) {
        EmptyStateView(
            icon = Icons.Filled.Lock,
            title = stringResource(R.string.common_not_logged_in),
            message = stringResource(R.string.common_login_required_feature),
        )
        return
    }
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }
    when (filter) {
        AssignmentFilter.INCOMPLETE -> EmptyStateView(
            icon = Icons.Filled.CheckCircle,
            title = stringResource(R.string.home_assignments_all_good),
            message = stringResource(R.string.home_assignments_none),
        )
        AssignmentFilter.ALL -> EmptyStateView(
            icon = Icons.Filled.Inbox,
            title = stringResource(R.string.home_assignments_none_now),
            message = "",
        )
        AssignmentFilter.IGNORED -> EmptyStateView(
            icon = Icons.Filled.VisibilityOff,
            title = stringResource(R.string.home_assignments_no_ignored),
            message = stringResource(R.string.home_assignments_ignore_hint),
        )
    }
}

/**
 * Assignment row with two swipe gestures, both following the SlotCard
 * pattern (0.6× drag damping, 100dp threshold, tween-out + snap-reset on
 * commit, spring-back otherwise):
 *  - Left swipe: toggle ignore (orange icon on the right edge — eye-off
 *    when not yet ignored, undo arrow when already ignored).
 *  - Right swipe: toggle mark-as-complete (green icon on the left edge —
 *    tick when not yet marked, undo arrow when already marked). The undo
 *    affordance is what makes 標示為完成 reversible from the 全部 tab.
 */
@Composable
private fun SwipeableAssignmentRow(
    assignment: Assignment,
    isIgnored: Boolean,
    isMarkedCompleted: Boolean,
    showAbsoluteTime: Boolean,
    onClick: () -> Unit,
    onToggleIgnore: () -> Unit,
    onMarkCompleted: () -> Unit,
) {
    val latestOnToggleIgnore by rememberUpdatedState(onToggleIgnore)
    val latestOnMarkCompleted by rememberUpdatedState(onMarkCompleted)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 100.dp.toPx() }
    val swipeOffset = remember(assignment.assignmentId) { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    // Modifier.offset { ... } and Alignment.CenterStart/End are rtl-aware,
    // but pointer deltas are raw screen-space. Negate in RTL so swipeOffset
    // stays "logical" (positive = start) and the visual row tracks the finger.
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val ignoreColor = Color(0xFFFF9500)
    val completeColor = Color(0xFF34C759)

    Box(modifier = Modifier.fillMaxWidth()) {
        val progress = (abs(swipeOffset.value) / thresholdPx).coerceIn(0f, 1f)

        // Left-edge mark/unmark icon: revealed when the row is dragged
        // right (positive offset). Tick when the row isn't marked yet,
        // revert-arrow when it is already marked (so 標示為完成 is
        // reversible without leaving the 全部 tab).
        if (swipeOffset.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = if (isMarkedCompleted) Icons.AutoMirrored.Filled.Undo
                                  else Icons.Filled.Check,
                    contentDescription = if (isMarkedCompleted) stringResource(R.string.assignment_mark_complete_undo) else stringResource(R.string.assignment_mark_complete),
                    tint = completeColor,
                    modifier = Modifier
                        .size(26.dp)
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress),
                )
            }
        }

        // Right-edge hide/undo icon: revealed when the row is dragged left
        // (negative offset).
        if (swipeOffset.value < 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = if (isIgnored) Icons.AutoMirrored.Filled.Undo
                                  else Icons.Filled.VisibilityOff,
                    contentDescription = if (isIgnored) stringResource(R.string.assignment_ignore_undo) else stringResource(R.string.assignment_ignore),
                    tint = ignoreColor,
                    modifier = Modifier
                        .size(26.dp)
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress),
                )
            }
        }

        // Opaque row that slides over the indicators. Surface color matches
        // the parent Card so no seam is visible at rest.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(assignment.assignmentId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                when {
                                    swipeOffset.value <= -thresholdPx -> {
                                        swipeOffset.animateTo(
                                            -2000f,
                                            animationSpec = tween(durationMillis = 200),
                                        )
                                        if (isRtl) latestOnMarkCompleted() else latestOnToggleIgnore()
                                        swipeOffset.snapTo(0f)
                                    }
                                    swipeOffset.value >= thresholdPx -> {
                                        swipeOffset.animateTo(
                                            2000f,
                                            animationSpec = tween(durationMillis = 200),
                                        )
                                        if (isRtl) latestOnToggleIgnore() else latestOnMarkCompleted()
                                        swipeOffset.snapTo(0f)
                                    }
                                    else -> swipeOffset.animateTo(0f, animationSpec = spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffset.animateTo(0f, animationSpec = spring())
                            }
                        },
                        onHorizontalDrag = { _, delta ->
                            val signedDelta = if (isRtl) -delta else delta
                            coroutineScope.launch {
                                swipeOffset.snapTo(swipeOffset.value + signedDelta * 0.6f)
                            }
                        },
                    )
                },
        ) {
            AssignmentItem(
                assignment = assignment,
                showAbsoluteTime = showAbsoluteTime,
                markedCompleted = isMarkedCompleted,
                onClick = onClick,
            )
        }
    }
}
