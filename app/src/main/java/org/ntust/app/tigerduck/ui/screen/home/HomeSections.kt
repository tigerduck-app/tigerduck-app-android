// Home's section machinery: the drag-to-reorder wrapper and the
// dispatch from a section id to the composable that renders it.

package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.navigation.icon
import kotlin.math.roundToInt

@Composable
internal fun ReorderableSection(
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
internal fun HomeSectionContent(
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
    onCourseClick: (Course, String) -> Unit,
    onAssignmentClick: (Assignment) -> Unit,
    onToggleIgnore: (Assignment) -> Unit,
    onMarkCompleted: (Assignment) -> Unit,
    onSelectFilter: (AssignmentFilter) -> Unit,
    onWidgetClick: () -> Unit,
    onOpenSignInSettings: () -> Unit,
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
                SectionHeader(
                    title = if (section.type == HomeSection.HomeSectionType.CUSTOM) section.title else stringResource(
                        section.type.defaultTitleRes
                    )
                )
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
                                val isMarkedCompleted =
                                    assignment.assignmentId in markedCompletedIds
                                SwipeableAssignmentRow(
                                    assignment = assignment,
                                    course = courseByNo[assignment.courseNo],
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
                SectionHeader(
                    title = if (section.type == HomeSection.HomeSectionType.CUSTOM) section.title else stringResource(
                        section.type.defaultTitleRes
                    )
                )
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
