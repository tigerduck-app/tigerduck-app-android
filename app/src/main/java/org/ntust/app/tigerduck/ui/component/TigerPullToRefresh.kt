package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * How far the current pull has come, 0..1, reaching 1 exactly where the
 * release would trigger a refresh.
 *
 * Ambient rather than a parameter because the thing that draws it — the
 * header's [org.ntust.app.tigerduck.ui.component.SyncStatusDot] — sits
 * several layers inside the pulled content on every screen that has one,
 * and threading a float down through each screen's header would be the
 * same plumbing written five times.
 *
 * A [State] rather than a bare `Float` so that a pull only re-runs the draw
 * of whoever reads it, instead of recomposing the whole content subtree
 * sixty times a second.
 */
val LocalPullProgress = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

private val ThresholdDp = 140.dp
private val MaxPullDp = 220.dp
private val RefreshingMessageOffset = 36.dp
private const val PostThresholdScale = 0.3f

@Composable
fun TigerPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Pull distance, 0..1, for a caller that needs it somewhere the
     * [LocalPullProgress] ambient cannot reach. The header dot reads the
     * ambient instead, so most screens leave this alone.
     */
    onDragProgress: (Float) -> Unit = {},
    refreshingMessage: String? = null,
    /** Optional hiding page chrome; null leaves scrolling exactly as it was. */
    appBar: AppBarState? = null,
    /** Optional search drawer that opens before the refresh pull; null leaves overscroll as it was. */
    searchReveal: SearchRevealState? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { ThresholdDp.toPx() }
    val maxPx = with(density) { MaxPullDp.toPx() }

    val dragY = remember { Animatable(0f) }
    val pullProgress = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val latestIsRefreshing by rememberUpdatedState(isRefreshing)
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    val latestOnDragProgress by rememberUpdatedState(onDragProgress)

    // True only while the user's finger is actively pulling. Cleared on
    // release so the "別急" hint disappears the moment we start the rebound,
    // even though dragY > 0 and isRefreshing is still true.
    val isUserPulling = remember { mutableStateOf(false) }

    // Whether a finger is actually on the screen right now.
    //
    // dragY used to be reset in exactly one place — onPreFling — so anything that grew it
    // without ending in a fling left the content translated down with no way back. A
    // programmatic bring-into-view (the keyboard opening under a focused search field) is
    // exactly that: it arrives as NestedScrollSource.UserInput, and no fling follows it.
    // Requiring a finger to be down before accumulating, and springing home when one lifts,
    // closes that whole class rather than the single path that was reported.
    //
    // UserInput is a wide door: mouse wheel and trackpad (NestedScrollSource.Wheel *is*
    // UserInput), arrow and page keys, accessibility scroll actions and bring-into-view all
    // arrive through it. None of them can arm a refresh any more. For a phone app that is the
    // behaviour we want, and the accessibility case is a straight fix: a TalkBack scroll could
    // previously strand the list translated down with no gesture able to bring it back.
    val fingerDown = remember { mutableStateOf(false) }

    // Set by onPreFling, read by the release effect below. A release that ends in a fling is
    // already rebounded there, and a second animateTo on the same Animatable would cancel the
    // first mid-flight — taking the rest of onPreFling down with it, crossedThreshold and the
    // swallowed velocity included. The effect stands aside whenever this is set.
    val releaseHandledByFling = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { dragY.value }.collect { y ->
            val progress = (y / thresholdPx).coerceIn(0f, 1f)
            pullProgress.floatValue = progress
            latestOnDragProgress(progress)
        }
    }

    // Keyed: the connection captures appBar and searchReveal, so a screen that swaps or
    // conditionally passes one would otherwise keep driving the instance it was born with
    // while the UI drew the new one.
    val connection = remember(appBar, searchReveal) {
        object : NestedScrollConnection {
            var crossedThreshold = false

            /** The settle currently running, for the next scroll to take the chrome back from. */
            var settleJob: Job? = null

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // The first delta of a real gesture takes the chrome off whatever spring was
                // settling it. Here rather than on the touch that began the gesture, because this
                // is the moment something else is about to write the same value — and because a
                // tap, a long press or a horizontal swipe on a row produces no delta, no fling
                // and so no second settle, and cancelling on those would leave the chrome frozen
                // part way with nothing coming to finish it. The spring runs on through the
                // touch-slop interval, which is right: that is not yet a scroll.
                if (source == NestedScrollSource.UserInput) settleJob?.cancel()
                var used = 0f
                // The refresh pull unwinds first: it is the last thing the finger raised, so it
                // is the first thing an upward move takes back. Draining the drawer ahead of it
                // would make the gesture irreversible — the drawer would shut while the content
                // still hung below the bar.
                //
                // Only ever under a real finger. The two guards are the hang fix and they stay
                // exactly as strict as they were: a programmatic bring-into-view, a wheel, an
                // accessibility scroll or a fling must never grow dragY, because none of them is
                // followed by a release that would bring it back down.
                if (source == NestedScrollSource.UserInput && fingerDown.value &&
                    available.y < 0f && dragY.value > 0f
                ) {
                    val consumed = maxOf(available.y, -dragY.value)
                    scope.launch { dragY.snapTo(dragY.value + consumed) }
                    if (crossedThreshold && dragY.value + consumed < thresholdPx) {
                        crossedThreshold = false
                    }
                    used += consumed
                }
                // Then the optional chrome takes its share of whatever is left over -- and it
                // takes it from a fling too, deliberately, where the pull above does not. The bar
                // has to keep tracking content that is still moving: settled at the lift instead,
                // a quick flick that had hidden it a third of the way would spring the header
                // back down over a list still travelling hundreds of pixels. It comes to rest in
                // onPostFling, when the scrolling has actually stopped.
                //
                // With both null this returns 0 and `used` is exactly what the block above
                // produced, which is exactly what this returned before any of it existed.
                used += chromeConsumption(available.y, used, appBar, searchReveal)
                return Offset(0f, used)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!fingerDown.value) return Offset.Zero
                if (available.y > 0f) {
                    // The list is at the top and still being pulled. The drawer gets the first
                    // bite; only what it cannot hold goes on to arm a refresh.
                    var used = 0f
                    searchReveal?.let { used += it.consume(available.y) }
                    val remaining = available.y - used
                    val delta = dampDelta(remaining, dragY.value, thresholdPx)
                    val newY = (dragY.value + delta).coerceIn(0f, maxPx)
                    scope.launch { dragY.snapTo(newY) }
                    if (newY > 0f) isUserPulling.value = true
                    if (!latestIsRefreshing) {
                        if (!crossedThreshold && newY >= thresholdPx) {
                            crossedThreshold = true
                            Haptics.perform(
                                context,
                                HapticScenario.PullToRefresh,
                            )
                        } else if (crossedThreshold && newY < thresholdPx) {
                            crossedThreshold = false
                        }
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                releaseHandledByFling.value = true
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

            /**
             * Where the chrome comes to rest. This is the one hook that means "the scrolling has
             * actually stopped": it arrives after the fling has decayed, and after a drag that
             * ended with no fling at all, because a release with no velocity still runs a fling of
             * zero length and still reports here.
             *
             * The next gesture has to be able to take the chrome back, and nothing does that for
             * us. Only `doFlingAnimation` runs inside `scroll(MutatePriority.Default)`; the
             * post-fling dispatch is launched beside it, on the dispatcher's own scope. So this
             * settle holds no mutex — it blocks nothing — and no mutex cancels it either. Left at
             * that, a spring and a finger would write `offsetPx` on alternate frames for a third
             * of a second while `onScroll` reported those pixels consumed and the list stood
             * still; flick, flick, flick is all it takes to see that.
             *
             * So the job is published here for `onPreScroll` to cancel on the next gesture's
             * first delta. `onPostScroll`'s drawer is covered by the same cancel, pre-scroll
             * running first in the same dispatch.
             *
             * A mouse wheel never arrives here at all: for wheel input `onScrollStopped` returns
             * before any fling is dispatched, so a wheel scroll moves the chrome and nothing
             * settles it. On a tablet, ChromeOS or DeX the bar can strand part way — one notch
             * moves it far less than its own height, and no touch gesture takes that path, so it
             * is left as it is.
             *
             * With no chrome it returns on the first line, leaving the four screens that pass
             * none with exactly the default implementation's behaviour.
             */
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (appBar == null && searchReveal == null) return Velocity.Zero
                coroutineScope {
                    val settles = launch {
                        searchReveal?.let { launch { it.settle() } }
                        appBar?.let { launch { it.settle() } }
                    }
                    settleJob = settles
                    try {
                        settles.join()
                    } finally {
                        // Only if it is still ours: a settle already replaced by a later one has
                        // handed the field over, and clearing it then would hide that one from
                        // the scroll that needs to cancel it.
                        if (settleJob === settles) settleJob = null
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // The finger lifted. onPreFling covers a release that ended in a fling; this covers a lift
    // with no fling at all — a cancelled gesture, a tap, or anything else that left dragY
    // raised — which otherwise sat there forever.
    LaunchedEffect(fingerDown.value) {
        if (fingerDown.value || dragY.value == 0f) return@LaunchedEffect
        // One frame of grace: the fling from this same pointer-up is dispatched on its own
        // coroutine, and it owns the rebound when it comes.
        withFrameNanos { }
        // Stand aside whenever anything already owns the Animatable. The flag covers the
        // release that produced the fling; isRunning additionally covers a tap landing during
        // that release's ~400ms rebound, which clears the flag by pressing but never crosses
        // touch slop, so no second onPreFling arrives to set it again. Two animateTo calls on
        // one Animatable cancel each other: the loser throws inside the still-suspended
        // onPreFling, skipping its `crossedThreshold = false` and stranding the next pull's
        // threshold haptic. isRunning is false when dragY was parked by snapTo — the hang this
        // effect exists for — so guarding on it takes nothing away.
        if (releaseHandledByFling.value || dragY.isRunning || dragY.value == 0f) {
            return@LaunchedEffect
        }
        isUserPulling.value = false
        dragY.animateTo(
            targetValue = 0f,
            animationSpec = spring(stiffness = 400f, dampingRatio = 0.9f),
        )
    }

    Box(
        modifier = modifier
            .nestedScroll(connection)
            // Watching on the Initial pass observes without competing for the gesture: nothing
            // is consumed here, so every child still sees the event exactly as before. The
            // finally matters — a cancelled pointer handler is restarted rather than sent an
            // "up", and fingerDown would otherwise be stuck true. It is paid for by the other
            // side of that: a handler reset mid-gesture (a density or view-configuration
            // change) reports the finger as up and can begin a rebound under a finger still on
            // the glass. The next move event sets it back, so the worst case is a jitter.
            .pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.any { it.pressed }
                            if (pressed) releaseHandledByFling.value = false
                            fingerDown.value = pressed
                        }
                    }
                } finally {
                    fingerDown.value = false
                }
            },
    ) {
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
            CompositionLocalProvider(LocalPullProgress provides pullProgress) {
                content()
            }
        }
    }
}

/**
 * How much of [availableY] the optional chrome takes, on top of the [alreadyUsed] the refresh
 * pull already took. Returns the *additional* amount, with the same sign as [availableY] and
 * never more than what is left — so a caller can report the sum as consumed and the list still
 * receives every pixel nobody claimed. With no chrome at all it returns 0, which is what keeps
 * the four screens that pass neither exactly where they were.
 */
internal fun chromeConsumption(
    availableY: Float,
    alreadyUsed: Float,
    appBar: AppBarState?,
    searchReveal: SearchRevealState?,
): Float {
    var used = 0f
    if (availableY < 0f) {
        // Scrolling up: close the drawer first, then hide the bar.
        searchReveal?.let { used += it.consume(availableY - alreadyUsed - used) }
        appBar?.let { used += it.onScroll(availableY - alreadyUsed - used) }
    } else if (availableY > 0f) {
        // Scrolling down anywhere: the bar comes back before the list moves.
        appBar?.let { used += it.onScroll(availableY - alreadyUsed - used) }
    }
    return used
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
