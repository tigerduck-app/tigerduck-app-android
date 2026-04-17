package org.ntust.app.tigerduck.ui.screen.home

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.*
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appState: AppState,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sections by viewModel.sections.collectAsState()
    val allCourses by viewModel.allCourses.collectAsState()
    val upcomingAssignments by viewModel.upcomingAssignments.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val selectedCourse by viewModel.selectedCourse.collectAsState()
    val skippedDates by viewModel.skippedDates.collectAsState()
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
                            SyncIndicator(isLoading = isLoading, showCheckmark = showCheckmark)
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
                            isLoggedIn = isLoggedIn,
                            hasUnfinishedAssignment = viewModel::hasUnfinishedAssignment,
                            showAbsoluteTime = appState.showAbsoluteAssignmentTime,
                            sliderStyle = appState.timeSliderStyle,
                            invertDirection = appState.invertSliderDirection,
                            skippedDates = skippedDates,
                            onCourseClick = {
                                if (!isEditing) viewModel.selectCourse(it)
                            },
                            onAssignmentClick = {
                                if (!isEditing) openAssignmentInMoodle(context, it)
                            },
                            onSkipCourse = { course, date ->
                                if (!isEditing) viewModel.toggleSkip(course, date)
                            },
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
    isLoggedIn: Boolean,
    hasUnfinishedAssignment: (String) -> Boolean,
    showAbsoluteTime: Boolean,
    sliderStyle: String,
    invertDirection: Boolean,
    skippedDates: Map<String, List<String>>,
    onCourseClick: (Course) -> Unit,
    onAssignmentClick: (Assignment) -> Unit,
    onSkipCourse: (Course, Date) -> Unit,
    onWidgetClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (section.type) {
            HomeSection.HomeSectionType.TODAY_COURSES -> {
                TimeSliderSection(
                    courses = allCourses,
                    sliderStyle = sliderStyle,
                    invertDirection = invertDirection,
                    skippedDates = skippedDates,
                    isLoggedIn = isLoggedIn,
                    onSkipCourse = onSkipCourse,
                    onSelectCourse = onCourseClick
                )
            }

            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> {
                SectionHeader(title = section.title)
                if (upcomingAssignments.isEmpty()) {
                    if (!isLoggedIn) {
                        EmptyStateView(
                            icon = Icons.Filled.Lock,
                            title = "尚未登入",
                            message = "請先登入以使用這項功能",
                        )
                    } else {
                        EmptyStateView(
                            icon = Icons.Filled.CheckCircle,
                            title = "一切順利",
                            message = "沒有待辦作業",
                        )
                    }
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
                            AssignmentItem(
                                assignment = assignment,
                                showAbsoluteTime = showAbsoluteTime,
                                onClick = { onAssignmentClick(assignment) }
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
                        items(section.widgets) { widget ->
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
