// The assignments section of Home — filter tabs, empty state, and the
// swipeable row with its done/ignore actions. Pulled out because it is
// the one part of Home with its own gesture and filter state.

package org.ntust.app.tigerduck.ui.screen.home

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.AssignmentItem
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.navigation.icon
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssignmentFilterTabs(
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
                Text(
                    stringResource(option.displayNameRes),
                    style = MaterialTheme.typography.labelMedium
                )
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
internal fun AssignmentsEmptyState(
    isLoggedIn: Boolean,
    isLoading: Boolean,
    filter: AssignmentFilter,
    onOpenSignInSettings: () -> Unit = {},
) {
    if (!isLoggedIn) {
        EmptyStateView(
            icon = Icons.Filled.Lock,
            title = stringResource(R.string.common_not_signed_in),
            message = stringResource(R.string.common_sign_in_required_feature),
            onIconClick = onOpenSignInSettings,
        )
        return
    }
    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
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
internal fun SwipeableAssignmentRow(
    assignment: Assignment,
    course: Course?,
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
    val ignoreColor = Color(0xFF8E8E93)
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
                    contentDescription = if (isMarkedCompleted) stringResource(R.string.assignment_mark_complete_undo) else stringResource(
                        R.string.assignment_mark_complete
                    ),
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
                    contentDescription = if (isIgnored) stringResource(R.string.assignment_ignore_undo) else stringResource(
                        R.string.assignment_ignore
                    ),
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
                course = course,
                showAbsoluteTime = showAbsoluteTime,
                markedCompleted = isMarkedCompleted,
                isIgnored = isIgnored,
                onClick = onClick,
            )
        }
    }
}
internal fun openAssignmentInMoodle(context: Context, assignment: Assignment) {
    val targets = listOfNotNull(assignment.moodleDeepLink, assignment.moodleUrl)
    for (target in targets) {
        val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (opened) return
    }
}
