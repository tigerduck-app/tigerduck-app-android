package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ScrollbarWidth = 3.dp
private val FastScrollWidth = 6.dp
private val ScrollbarInset = 2.dp
private const val MinThumbPx = 40f

/**
 * How far in from the edge a long press still takes hold of the thumb. Wider than the thumb, which
 * is too thin to aim at, and free to overlap the rows: only a long press held in place claims the
 * touch, so a tap, a scroll or a swipe that starts here is still the list's.
 */
private val FastScrollTouchWidth = 20.dp

/**
 * Where the thumb sits, as fractions of its track: [offset] 0 at the top and 1 at the bottom of
 * its travel, [size] the share of the track it covers.
 */
internal data class ScrollbarThumb(val offset: Float, val size: Float)

/**
 * A thin scroll indicator on the trailing edge of a [verticalScroll][androidx.compose.foundation.verticalScroll]
 * container. It shows while the content moves and fades once it stops, and never appears for
 * content that fits. A long press on the edge takes hold of it for fast scrolling.
 *
 * Goes *before* `verticalScroll` in the chain, so it draws over the viewport. After it, it would
 * be laid out and drawn inside the scrolled content, the whole content's height tall and moving
 * with it.
 */
@Composable
fun Modifier.scrollbar(state: ScrollState): Modifier {
    var fastScrolling by remember(state) { mutableStateOf(false) }
    val look = scrollbarLook(state.isScrollInProgress, fastScrolling)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val direction = LocalLayoutDirection.current
    return this
        .pointerInput(state, direction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (state.maxValue <= 0) return@awaitEachGesture
                if (!isOnTrailingEdge(down.position.x, size.width.toFloat(), FastScrollTouchWidth.toPx(), direction)) {
                    return@awaitEachGesture
                }
                if (!awaitLongPressInPlace(down)) return@awaitEachGesture
                fastScrolling = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                try {
                    followFinger(down) { y ->
                        val viewport = size.height.toFloat()
                        val max = state.maxValue
                        val thumb = thumbHeightPx(viewport, viewport / (viewport + max))
                        val fraction = fastScrollFraction(y, trackTop = 0f, track = viewport, thumbHeight = thumb)
                        scope.launch { state.scrollTo((fraction * max).roundToInt()) }
                    }
                } finally {
                    fastScrolling = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            val max = state.maxValue
            if (max <= 0) return@drawWithContent
            val viewport = size.height
            drawThumb(ScrollbarThumb(state.value.toFloat() / max, viewport / (viewport + max)), 0f, look)
        }
}

/**
 * The same indicator for a `LazyColumn`, whose full height is never measured. It is estimated:
 * every row keeps the height it had when last laid out, and only rows never yet on screen are
 * guessed at, from the average of the ones that were. A message -- a short header over one very
 * tall body -- is exact after its first frame; a long list converges as it is scrolled.
 *
 * A long press on the trailing edge takes hold of it: the thumb widens into the accent colour and
 * the list jumps to wherever the finger is along the track, until it lifts.
 *
 * [topInsetPx] keeps the track clear of anything drawn over the top of the list -- a page's
 * chrome overlay, which would otherwise hide the thumb whenever the list is near its top. Read
 * at draw time, so a moving chrome only redraws the thumb.
 *
 * [onFastScrollStopped] runs when the finger lets go. A fast scroll jumps the list with
 * `scrollToItem`, which dispatches no nested scroll, so anything that follows the list's scrolling
 * -- a hiding app bar -- hears nothing of it and may need putting right.
 */
@Composable
fun Modifier.scrollbar(
    state: LazyListState,
    topInsetPx: () -> Float = { 0f },
    onFastScrollStopped: () -> Unit = {},
): Modifier {
    var fastScrolling by remember(state) { mutableStateOf(false) }
    val look = scrollbarLook(state.isScrollInProgress, fastScrolling)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val direction = LocalLayoutDirection.current
    val sizes = remember(state) { ItemSizes() }
    val latestTopInset by rememberUpdatedState(topInsetPx)
    val latestOnStopped by rememberUpdatedState(onFastScrollStopped)
    return this
        .pointerInput(state, direction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val height = size.height.toFloat()
                if (!isOnTrailingEdge(down.position.x, size.width.toFloat(), FastScrollTouchWidth.toPx(), direction)) {
                    return@awaitEachGesture
                }
                if (down.position.y < latestTopInset().coerceIn(0f, height)) return@awaitEachGesture
                if (!state.canScrollBackward && !state.canScrollForward) return@awaitEachGesture
                if (!awaitLongPressInPlace(down)) return@awaitEachGesture
                // The guess for unseen rows is held for the whole drag: re-averaging on every move
                // would let a stretch of tall rows coming on screen drag the list away under a
                // finger that had not moved.
                sizes.record(state.layoutInfo)
                val guess = sizes.average() ?: return@awaitEachGesture
                val sizeOf = { index: Int -> sizes.of(index) ?: guess }
                fastScrolling = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                try {
                    followFinger(down) { y ->
                        val info = state.layoutInfo
                        sizes.record(info)
                        val trackTop = latestTopInset().coerceIn(0f, height)
                        val track = height - trackTop
                        val thumb = lazyListScrollbarThumb(info, state.canScrollBackward, state.canScrollForward, sizeOf)
                            ?: return@followFinger
                        val fraction = fastScrollFraction(y, trackTop, track, thumbHeightPx(track, thumb.size))
                        val (index, offset) = fastScrollTarget(fraction, info, sizeOf)
                        scope.launch { state.scrollToItem(index, offset) }
                    }
                } finally {
                    fastScrolling = false
                    latestOnStopped()
                }
            }
        }
        .drawWithContent {
            drawContent()
            val info = state.layoutInfo
            sizes.record(info)
            val guess = sizes.average() ?: return@drawWithContent
            val thumb = lazyListScrollbarThumb(
                info,
                canScrollBackward = state.canScrollBackward,
                canScrollForward = state.canScrollForward,
                sizeOf = { sizes.of(it) ?: guess },
            ) ?: return@drawWithContent
            drawThumb(thumb, latestTopInset().coerceIn(0f, size.height), look)
        }
}

/**
 * The heights rows had when last laid out, by index, spacing included -- the estimate's memory.
 *
 * Forgotten whenever the list's length changes: a page appended, a filter applied. Indices then
 * name other rows, and a stale height is worse than the average standing in until each row is
 * seen again. Plain fields rather than snapshot state: it is written from the draw pass, and
 * nothing should recompose or redraw because a height was remembered.
 */
internal class ItemSizes {
    private var count = -1
    private val sizes = HashMap<Int, Int>()

    fun record(info: LazyListLayoutInfo) {
        if (info.totalItemsCount != count) {
            sizes.clear()
            count = info.totalItemsCount
        }
        for (item in info.visibleItemsInfo) sizes[item.index] = item.size + info.mainAxisItemSpacing
    }

    fun of(index: Int): Float? = sizes[index]?.toFloat()

    /** The stand-in for a row never laid out; null until anything has been. */
    fun average(): Float? = if (sizes.isEmpty()) null else sizes.values.sum().toFloat() / sizes.size
}

/** The list's estimated full length and how far into it the viewport starts, both in px. */
private class ListExtent(val content: Float, val scrolled: Float, val viewport: Float)

private fun listExtent(info: LazyListLayoutInfo, sizeOf: (Int) -> Float): ListExtent? {
    val first = info.visibleItemsInfo.firstOrNull() ?: return null
    if (info.totalItemsCount <= 0) return null
    var before = 0f
    var rows = 0f
    for (index in 0 until info.totalItemsCount) {
        val size = sizeOf(index)
        if (index < first.index) before += size
        rows += size
    }
    return ListExtent(
        content = info.beforeContentPadding + rows + info.afterContentPadding,
        scrolled = info.beforeContentPadding + before - first.offset + info.viewportStartOffset,
        viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
    )
}

/**
 * Where the thumb sits, from [sizeOf]'s height for every row. Null when the list fits and there
 * is nothing to show.
 *
 * The two ends are taken from the list rather than the estimate, so the thumb reaches the very
 * top and bottom of its track exactly when the list does, however rough the guesses are.
 */
internal fun lazyListScrollbarThumb(
    info: LazyListLayoutInfo,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    sizeOf: (Int) -> Float,
): ScrollbarThumb? {
    if (!canScrollBackward && !canScrollForward) return null
    val extent = listExtent(info, sizeOf) ?: return null
    val maxScroll = extent.content - extent.viewport
    if (maxScroll <= 0f || extent.viewport <= 0f) return null
    val offset = when {
        !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> (extent.scrolled / maxScroll).coerceIn(0f, 1f)
    }
    return ScrollbarThumb(offset = offset, size = (extent.viewport / extent.content).coerceIn(0f, 1f))
}

/**
 * Where along its travel the thumb goes for a finger at [y]: centred under the finger, clamped to
 * the two ends. 0 for a thumb that fills its track and has nowhere to travel.
 */
internal fun fastScrollFraction(y: Float, trackTop: Float, track: Float, thumbHeight: Float): Float {
    val travel = track - thumbHeight
    if (travel <= 0f) return 0f
    return ((y - trackTop - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
}

/**
 * The row, and the offset into it, that put the list [fraction] of the way down by the same
 * heights the thumb is drawn from. The bottom asks for the last row outright, which the list
 * clamps to its true end rather than to wherever the estimate says the end is.
 */
internal fun fastScrollTarget(fraction: Float, info: LazyListLayoutInfo, sizeOf: (Int) -> Float): Pair<Int, Int> {
    val total = info.totalItemsCount
    if (total <= 0) return 0 to 0
    if (fraction >= 1f) return (total - 1) to 0
    val extent = listExtent(info, sizeOf) ?: return 0 to 0
    // Scroll 0 puts row 0 at the top of the content, below the leading padding, so the target is
    // measured in rows alone.
    var remaining = fraction.coerceAtLeast(0f) * (extent.content - extent.viewport).coerceAtLeast(0f)
    for (index in 0 until total) {
        val size = sizeOf(index)
        if (remaining < size) return index to remaining.roundToInt()
        remaining -= size
    }
    return (total - 1) to 0
}

internal fun isOnTrailingEdge(x: Float, width: Float, edge: Float, direction: LayoutDirection): Boolean =
    if (direction == LayoutDirection.Rtl) x <= edge else x >= width - edge

/**
 * Waits out a long press without claiming anything. False as soon as the finger lifts, moves past
 * touch slop, or something else takes the event -- a tap or a scroll that merely started on the
 * edge, which the list then handles exactly as it would have without this.
 */
private suspend fun AwaitPointerEventScope.awaitLongPressInPlace(down: PointerInputChange): Boolean {
    val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var gaveUp = false
        while (!gaveUp) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            gaveUp = change == null || !change.pressed || change.isConsumed ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        }
        true
    }
    return interrupted == null
}

/**
 * Hands [onMove] the finger's height, from where it was held until it lifts, consuming every event
 * on the Initial pass so the list underneath never starts a drag of its own.
 */
private suspend fun AwaitPointerEventScope.followFinger(down: PointerInputChange, onMove: (Float) -> Unit) {
    onMove(down.position.y)
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return
        change.consume()
        if (!change.pressed) return
        onMove(change.position.y)
    }
}

private class ScrollbarLook(val alpha: State<Float>, val width: State<Dp>, val color: Color)

@Composable
private fun scrollbarLook(scrolling: Boolean, fastScrolling: Boolean): ScrollbarLook {
    val shown = scrolling || fastScrolling
    val alpha = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        // Twice as long to go as it used to take: held for half a second, then faded over the
        // next. The hold is what leaves time to long-press it -- a slower fade alone spends most
        // of its change in the first few frames, so the thumb looked gone almost as soon.
        animationSpec = if (shown) tween(durationMillis = 150) else tween(durationMillis = 500, delayMillis = 500),
        label = "scrollbar_alpha",
    )
    val width = animateDpAsState(
        targetValue = if (fastScrolling) FastScrollWidth else ScrollbarWidth,
        label = "scrollbar_width",
    )
    val color = if (fastScrolling) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }
    return ScrollbarLook(alpha, width, color)
}

private fun thumbHeightPx(track: Float, sizeFraction: Float): Float =
    (track * sizeFraction).coerceIn(minOf(MinThumbPx, track), track)

private fun ContentDrawScope.drawThumb(thumb: ScrollbarThumb, trackTop: Float, look: ScrollbarLook) {
    val alpha = look.alpha.value
    if (alpha <= 0f) return
    val track = size.height - trackTop
    if (track <= 0f) return
    val thumbHeight = thumbHeightPx(track, thumb.size)
    val width = look.width.value.toPx()
    val inset = ScrollbarInset.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) inset else size.width - width - inset
    drawRoundRect(
        color = look.color.copy(alpha = look.color.alpha * alpha),
        topLeft = Offset(x, trackTop + thumb.offset * (track - thumbHeight)),
        size = Size(width, thumbHeight),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}
