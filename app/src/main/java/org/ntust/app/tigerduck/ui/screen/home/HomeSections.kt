// Home's section machinery: a section browsed, a section arranged (its
// outline, drag handle and remove button), and the dispatch from a
// section's type to the composable that renders it.

package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.component.SectionHeader
import kotlin.math.roundToInt

/** A section while Home is browsed: its content, and a long press anywhere on it starts editing. */
@Composable
internal fun BrowsedSection(
    section: HomeSection,
    onStartEditing: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Only the long press lives here, so taps on the cards inside are never
    // stolen by this gesture detector.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(section.id) {
                detectTapGestures(onLongPress = { onStartEditing() })
            }
    ) {
        content()
    }
}

/**
 * A section while Home is arranged, as iOS draws it (tigerduck-app/tigerduck-app#852): its header
 * and the shape of its rows, so the order reads at a glance and nothing in it loads, updates or
 * takes a tap. The drag handle sits above the header, which never covers it, with the remove
 * button at its end, named for the section. A section of shortcuts keeps its cards, which move
 * and remove on their own.
 */
@Composable
internal fun EditableSection(
    section: HomeSection,
    isDragging: Boolean,
    dragOffsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onMeasured: (height: Float) -> Unit,
    onRemove: () -> Unit,
    onRemoveWidget: (widgetId: String) -> Unit,
    onMoveWidget: (widgetId: String, toIndex: Int) -> Unit,
    onAddQuickFeature: () -> Unit,
) {
    // Material-style elevation ramp for the dragging section — no wiggle.
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 12f else 0f,
        label = "drag-elevation",
    )
    val shape = RoundedCornerShape(18.dp)
    val title = homeSectionTitle(section)
    val removeLabel = stringResource(R.string.action_remove)
    // The drag runs inside pointerInput, which is keyed by the section and
    // not restarted by the swaps it makes, so it reaches the callbacks
    // through these: the ones it began with hold the order from before.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // A swap can put a lifted section's place above or below the screen while
    // the drag still draws it under the finger; pinned, the list keeps it
    // composed there, and the drag with it.
    val pinnable = LocalPinnableContainer.current
    DisposableEffect(isDragging, pinnable) {
        val pinned = if (isDragging) pinnable?.pin() else null
        onDispose { pinned?.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height.toFloat()) }
            // Over its neighbors while lifted, whichever way the drag takes it.
            .zIndex(if (isDragging) 1f else 0f)
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .then(
                // The outline has no fill of its own, so the lifted one takes the page's, or its
                // shadow would show through it.
                if (isDragging) {
                    Modifier
                        .shadow(elevation.dp, shape)
                        .background(MaterialTheme.colorScheme.background, shape)
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // Starts the reorder on first touch-and-move, no long press needed;
            // the rest of the outline is left to scroll the page.
            SectionDragHandle(
                modifier = Modifier
                    .align(Alignment.Center)
                    .pointerInput(section.id) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragEnd() },
                            onDrag = { change, drag ->
                                change.consume()
                                currentOnDrag(drag.y)
                            },
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(enabled = !isDragging, role = Role.Button) { onRemove() }
                    // Every section's cross saying only "Remove" could not be told apart;
                    // named as a shortcut card's is.
                    .semantics { contentDescription = "$removeLabel $title" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        SectionHeader(title = title)
        when (section.type) {
            HomeSection.HomeSectionType.TODAY_COURSES -> TimeSliderOutline()
            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> AssignmentsOutline()
            HomeSection.HomeSectionType.QUICK_WIDGETS,
            HomeSection.HomeSectionType.CUSTOM -> ShortcutGrid(
                widgets = section.widgets,
                isEditing = true,
                onOpen = {},
                onRemove = { onRemoveWidget(it.id) },
                onMove = onMoveWidget,
                onAdd = onAddQuickFeature,
            )
        }
    }
}

/** The title a section's header shows, browsed or arranged, and its remove button names. */
@Composable
@ReadOnlyComposable
internal fun homeSectionTitle(section: HomeSection): String = when (section.type) {
    // The time slider's own header, whatever the section was first titled.
    HomeSection.HomeSectionType.TODAY_COURSES -> stringResource(R.string.home_time_slider_title)
    HomeSection.HomeSectionType.CUSTOM -> section.title
    else -> stringResource(section.type.defaultTitleRes)
}

@Composable
private fun SectionDragHandle(modifier: Modifier = Modifier) {
    // The pill is what shows; the 48dp around it is what takes the drag.
    Box(modifier = modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.home_section_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The time slider's shape: its course card and its track. Decoration, so TalkBack skips it. */
@Composable
private fun TimeSliderOutline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlineCard { OutlineLines(Modifier.padding(16.dp)) }
        OutlineBar(height = TimeSliderViewModel.FLUID_TRACK_HEIGHT.dp)
    }
}

/** The assignments' shape: the filter row and three rows of the card under it. */
@Composable
private fun AssignmentsOutline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlineBar(height = 32.dp)
        OutlineCard {
            repeat(3) { row ->
                if (row > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                OutlineLines(Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun OutlineCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

/** A row's two lines of text, the first one shorter. */
@Composable
private fun OutlineLines(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineBar(height = 10.dp, modifier = Modifier.fillMaxWidth(0.6f))
        OutlineBar(height = 10.dp)
    }
}

@Composable
private fun OutlineBar(height: Dp, modifier: Modifier = Modifier.fillMaxWidth()) {
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
internal fun HomeSectionContent(
    section: HomeSection,
    allCourses: List<Course>,
    /** The time slider's state, held above the section so editing does not reset it. */
    timeSlider: TimeSliderViewModel,
    quietDays: QuietDays,
    upcomingAssignments: List<Assignment>,
    assignmentFilter: AssignmentFilter,
    showIgnoredTab: Boolean,
    ignoredAssignmentIds: Set<String>,
    markedCompletedIds: Set<String>,
    courseIgnoredIds: Set<String>,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    initialLoadComplete: Boolean,
    hasUnfinishedAssignment: (String) -> Boolean,
    showAbsoluteTime: Boolean,
    invertDirection: Boolean,
    onCourseClick: (CourseTimeSlot) -> Unit,
    onAssignmentClick: (Assignment) -> Unit,
    onToggleIgnore: (Assignment) -> Unit,
    onMarkCompleted: (Assignment) -> Unit,
    onSelectFilter: (AssignmentFilter) -> Unit,
    /** A shortcut card tapped; HomeScreen opens the page or says "Coming soon". */
    onOpenFeature: (AppFeature) -> Unit,
    /** An empty shortcut grid's button: the Add dialog. */
    onAddQuickFeature: () -> Unit,
    onOpenSignInSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (section.type) {
            HomeSection.HomeSectionType.TODAY_COURSES -> {
                TimeSliderSection(
                    viewModel = timeSlider,
                    courses = allCourses,
                    quietDays = quietDays,
                    invertDirection = invertDirection,
                    isLoggedIn = isLoggedIn,
                    initialLoadComplete = initialLoadComplete,
                    onSignIn = onOpenSignInSettings,
                    onSelectCourse = onCourseClick
                )
            }

            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> {
                SectionHeader(title = homeSectionTitle(section))
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
                        onOpenSignInSettings = onOpenSignInSettings,
                    )
                } else {
                    // Resolve the canonical Course for each row's courseNo so
                    // the "name • courseNo" label reflects user renames and
                    // the real NTUST code (iOS parity). Memoized to avoid
                    // rebuilding the map on unrelated state changes.
                    val courseByNo = remember(allCourses) {
                        allCourses.associateBy { it.courseNo }
                    }
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
                                // Ignored with its course: shown as ignored even
                                // if marked done, and only the class table
                                // undoes it, so the row takes no swipe.
                                val ignoredWithCourse = assignment.assignmentId in courseIgnoredIds
                                SwipeableAssignmentRow(
                                    assignment = assignment,
                                    course = courseByNo[assignment.courseNo],
                                    isIgnored = ignoredWithCourse ||
                                        assignment.assignmentId in ignoredAssignmentIds,
                                    isMarkedCompleted = !ignoredWithCourse &&
                                        assignment.assignmentId in markedCompletedIds,
                                    swipeEnabled = !ignoredWithCourse,
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

            HomeSection.HomeSectionType.QUICK_WIDGETS,
            HomeSection.HomeSectionType.CUSTOM -> {
                SectionHeader(title = homeSectionTitle(section))
                // Arranged, the section is an EditableSection, with a grid of its own.
                ShortcutGrid(
                    widgets = section.widgets,
                    isEditing = false,
                    onOpen = onOpenFeature,
                    onRemove = {},
                    onMove = { _, _ -> },
                    onAdd = onAddQuickFeature,
                )
            }
        }
    }
}
