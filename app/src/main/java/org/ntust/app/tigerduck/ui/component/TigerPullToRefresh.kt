package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

private val ThresholdDp = 140.dp
private val MaxPullDp = 220.dp
private val RefreshingMessageOffset = 36.dp
private const val PostThresholdScale = 0.3f

@Composable
fun TigerPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDragProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
    refreshingMessage: String? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { ThresholdDp.toPx() }
    val maxPx = with(density) { MaxPullDp.toPx() }

    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val latestIsRefreshing by rememberUpdatedState(isRefreshing)
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    val latestOnDragProgress by rememberUpdatedState(onDragProgress)

    // True only while the user's finger is actively pulling. Cleared on
    // release so the "別急" hint disappears the moment we start the rebound,
    // even though dragY > 0 and isRefreshing is still true.
    val isUserPulling = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { dragY.value }.collect { y ->
            latestOnDragProgress((y / thresholdPx).coerceIn(0f, 1f))
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            var crossedThreshold = false

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0f && dragY.value > 0f) {
                    val consumed = maxOf(available.y, -dragY.value)
                    scope.launch { dragY.snapTo(dragY.value + consumed) }
                    if (crossedThreshold && dragY.value + consumed < thresholdPx) {
                        crossedThreshold = false
                    }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f) {
                    val delta = dampDelta(available.y, dragY.value, thresholdPx)
                    val newY = (dragY.value + delta).coerceIn(0f, maxPx)
                    scope.launch { dragY.snapTo(newY) }
                    if (newY > 0f) isUserPulling.value = true
                    if (!latestIsRefreshing) {
                        if (!crossedThreshold && newY >= thresholdPx) {
                            crossedThreshold = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else if (crossedThreshold && newY < thresholdPx) {
                            crossedThreshold = false
                        }
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragY.value > 0f) {
                    val triggered = dragY.value >= thresholdPx && !latestIsRefreshing
                    if (triggered) latestOnRefresh()
                    isUserPulling.value = false
                    dragY.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(stiffness = 400f, dampingRatio = 0.9f),
                    )
                    crossedThreshold = false
                    return available
                }
                isUserPulling.value = false
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        Box(modifier = Modifier.graphicsLayer { translationY = dragY.value }) {
            if (isRefreshing && refreshingMessage != null && dragY.value > 0f
                && isUserPulling.value
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -RefreshingMessageOffset)
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = refreshingMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = ContentAlpha.SECONDARY),
                    )
                }
            }
            content()
        }
    }
}

private fun dampDelta(delta: Float, currentY: Float, threshold: Float): Float {
    return if (currentY < threshold) {
        val roomLinear = threshold - currentY
        if (delta <= roomLinear) delta
        else roomLinear + (delta - roomLinear) * PostThresholdScale
    } else {
        delta * PostThresholdScale
    }
}
