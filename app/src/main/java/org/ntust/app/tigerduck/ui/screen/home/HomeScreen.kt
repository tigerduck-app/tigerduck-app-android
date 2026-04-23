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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.*
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
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val allCourses by viewModel.allCourses.collectAsStateWithLifecycle()
    val upcomingAssignments by viewModel.upcomingAssignments.collectAsStateWithLifecycle()
    val assignmentFilter by viewModel.assignmentFilter.collectAsStateWithLifecycle()
    val ignoredAssignmentIds by viewModel.ignoredAssignmentIds.collectAsStateWithLifecycle()
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

    LaunchedEffect(Unit) {
        viewModel.syncCompleteEvent.collect {
            showCheckmark = true
            delay(2000)
            showCheckmark = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar("無法連線，請檢查網路連線")
        }
    }

    var pullProgress by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            onDragProgress = { pullProgress = it },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = "頁面正在刷新，別急～",
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
                                Icon(Icons.Filled.Add, contentDescription = "新增區塊")
                            }
                            TextButton(onClick = { isEditing = false }) {
                                Text("完成", fontWeight = FontWeight.SemiBold)
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
                            ignoredAssignmentIds = ignoredAssignmentIds,
                            isLoggedIn = isLoggedIn,
                            isLoading = isLoading,
                            initialLoadComplete = initialLoadComplete,
                            hasUnfinishedAssignment = viewModel::hasUnfinishedAssignment,
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
                viewModel.addSection(type, type.defaultTitle)
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
        CourseDetailDialog(
            course = course,
            assignments = viewModel.assignmentsFor(course.courseNo),
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
                        contentDescription = "拖曳排序",
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
                        contentDescription = "移除",
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
    ignoredAssignmentIds: Set<String>,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    initialLoadComplete: Boolean,
    hasUnfinishedAssignment: (String) -> Boolean,
    showAbsoluteTime: Boolean,
    invertDirection: Boolean,
    onCourseClick: (Course) -> Unit,
    onAssignmentClick: (Assignment) -> Unit,
    onToggleIgnore: (Assignment) -> Unit,
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
                SectionHeader(title = section.title)
                AssignmentFilterTabs(
                    selected = assignmentFilter,
                    enabled = isLoggedIn,
                    onSelect = onSelectFilter,
                )
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
                            SwipeableAssignmentRow(
                                assignment = assignment,
                                isIgnored = assignment.assignmentId in ignoredAssignmentIds,
                                showAbsoluteTime = showAbsoluteTime,
                                onClick = { onAssignmentClick(assignment) },
                                onToggleIgnore = { onToggleIgnore(assignment) },
                            )
                            if (index < upcomingAssignments.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            HomeSection.HomeSectionType.QUICK_WIDGETS,
            HomeSection.HomeSectionType.CUSTOM -> {
                SectionHeader(title = section.title)
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
    val hour = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "還不睡？"
        hour < 12 -> "早安"
        hour < 18 -> "午安"
        else -> "晚安"
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
    onSelect: (AssignmentFilter) -> Unit,
) {
    val options = AssignmentFilter.entries
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
            ) {
                Text(option.displayName)
            }
        }
    }
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
            title = "尚未登入",
            message = "請先登入以使用這項功能",
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
            title = "一切順利",
            message = "沒有待辦作業",
        )
        AssignmentFilter.ALL -> EmptyStateView(
            icon = Icons.Filled.Inbox,
            title = "目前沒有作業",
            message = "",
        )
        AssignmentFilter.IGNORED -> EmptyStateView(
            icon = Icons.Filled.VisibilityOff,
            title = "沒有已忽略的作業",
            message = "向左滑動作業以將其忽略",
        )
    }
}

/**
 * Assignment row with left-swipe-to-toggle-ignore, mirroring the SlotCard
 * gesture pattern: drag damped at 0.6×, 100dp threshold, tween-out + snap-reset
 * on commit, spring-back otherwise.
 */
@Composable
private fun SwipeableAssignmentRow(
    assignment: Assignment,
    isIgnored: Boolean,
    showAbsoluteTime: Boolean,
    onClick: () -> Unit,
    onToggleIgnore: () -> Unit,
) {
    val latestOnToggle by rememberUpdatedState(onToggleIgnore)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 100.dp.toPx() }
    val swipeOffset = remember(assignment.assignmentId) { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val actionColor = Color(0xFFFF9500)

    Box(modifier = Modifier.fillMaxWidth()) {
        // Indicator painted behind the sliding row. It becomes visible as the
        // row slides left and exposes the right-edge area.
        val progress = (abs(swipeOffset.value) / thresholdPx).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier
                    .alpha(progress)
                    .scale(0.5f + 0.5f * progress),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (isIgnored) Icons.AutoMirrored.Filled.Undo
                                  else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isIgnored) "取消忽略" else "忽略",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = actionColor,
                    fontSize = 11.sp,
                )
            }
        }

        // Opaque row that slides over the indicator. The surface color matches
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
                                if (swipeOffset.value <= -thresholdPx) {
                                    swipeOffset.animateTo(
                                        -2000f,
                                        animationSpec = tween(durationMillis = 200),
                                    )
                                    latestOnToggle()
                                    swipeOffset.snapTo(0f)
                                } else {
                                    swipeOffset.animateTo(0f, animationSpec = spring())
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                swipeOffset.animateTo(0f, animationSpec = spring())
                            }
                        },
                        onHorizontalDrag = { _, delta ->
                            coroutineScope.launch {
                                swipeOffset.snapTo(
                                    (swipeOffset.value + delta * 0.6f).coerceAtMost(0f),
                                )
                            }
                        },
                    )
                },
        ) {
            AssignmentItem(
                assignment = assignment,
                showAbsoluteTime = showAbsoluteTime,
                onClick = onClick,
            )
        }
    }
}
