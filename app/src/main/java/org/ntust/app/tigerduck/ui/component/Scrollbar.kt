package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ScrollbarWidth = 3.dp
private val FastScrollWidth = 6.dp
private val ScrollbarInset = 2.dp
private const val MinThumbPx = 40f

/**
 * How far in from the edge a long press still takes hold of the thumb: the minimum touch target,
 * where it used to be 20dp. The last few millimetres of a screen are the worst place on a phone to
 * hold a finger still: at 20dp a long press there took on a Moto G34, barely on a Zenfone 6 and not
 * at all on a Galaxy A26, and HyperOS lays a back-gesture window over exactly those 20dp. So the
 * zone reaches well in past all of that.
 *
 * It overlaps the rows, and a mail's selectable text, which is why it is only live while the thumb
 * shows. Even then only a long press held in place claims the touch, so a tap, a scroll or a swipe
 * that starts here is still the list's.
 */
private val FastScrollTouchWidth = 48.dp

/**
 * Where the thumb sits, as fractions of its track: [offset] 0 at the top and 1 at the bottom of
 * its travel, [size] the share of the track it covers.
 */
internal data class ScrollbarThumb(val offset: Float, val size: Float)

/**
 * A thin scroll indicator on the trailing edge of a [verticalScroll][androidx.compose.foundation.verticalScroll]
 * container. It shows while the content moves and fades once it stops, and never appears for
 * content that fits. While it shows, a long press on the edge takes hold of it for fast scrolling.
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
                if (look.alpha.value <= 0f || state.maxValue <= 0) return@awaitEachGesture
                if (!isOnTrailingEdge(down.position.x, size.width.toFloat(), FastScrollTouchWidth.toPx(), direction)) {
                    return@awaitEachGesture
                }
                if (!awaitLongPressInPlace(down)) return@awaitEachGesture
                fastScrolling = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // One session at UserInput for the whole drag -- see the LazyListState overload.
                val targets = Channel<Int>(Channel.CONFLATED)
                scope.launch {
                    state.scroll(MutatePriority.UserInput) {
                        for (target in targets) scrollBy((target - state.value).toFloat())
                    }
                }
                try {
                    followFinger(down) { y ->
                        val viewport = size.height.toFloat()
                        val max = state.maxValue
                        val thumb = thumbHeightPx(viewport, viewport / (viewport + max))
                        val fraction = fastScrollFraction(y, trackTop = 0f, track = viewport, thumbHeight = thumb)
                        targets.trySend((fraction * max).roundToInt())
                    }
                } finally {
                    targets.close()
                    fastScrolling = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            scrollThumb(state, size.height)?.let { drawThumb(it, look) }
        }
        .thumbGestureExclusion { height ->
            if (look.alpha.value <= 0f || fastScrolling || state.isScrollInProgress) null else scrollThumb(state, height)
        }
}

private fun scrollThumb(state: ScrollState, height: Float): ThumbSpan? {
    val max = state.maxValue
    if (max <= 0) return null
    return thumbSpan(ScrollbarThumb(state.value.toFloat() / max, height / (height + max)), trackTop = 0f, height)
}

/**
 * The same indicator for a `LazyColumn`, whose full height is never measured. It is estimated:
 * every row keeps the height it had when last laid out, and only rows never yet on screen are
 * guessed at, from the average of the ones that were. A message -- a short header over one very
 * tall body -- is exact after its first frame; a long list converges as it is scrolled.
 *
 * While it shows, a long press on the trailing edge takes hold of it: the thumb widens into the
 * accent colour and the list jumps to wherever the finger is along the track, until it lifts.
 *
 * [topInsetPx] keeps the track clear of anything drawn over the top of the list -- a page's
 * chrome overlay, which would otherwise hide the thumb whenever the list is near its top. Read
 * at draw time, so a moving chrome only redraws the thumb.
 *
 * [onFastScrollStopped] runs when the finger lets go. A fast scroll jumps the list from row to row,
 * which dispatches no nested scroll, so anything that follows the list's scrolling -- a hiding app
 * bar -- hears nothing of it and may need putting right.
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
    fun thumbOf(height: Float): ThumbSpan? {
        val info = state.layoutInfo
        sizes.record(info)
        val guess = sizes.average() ?: return null
        val thumb = lazyListScrollbarThumb(
            info,
            canScrollBackward = state.canScrollBackward,
            canScrollForward = state.canScrollForward,
            sizeOf = { sizes.of(it) ?: guess },
        ) ?: return null
        return thumbSpan(thumb, latestTopInset().coerceIn(0f, height), height)
    }
    return this
        .pointerInput(state, direction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (look.alpha.value <= 0f) return@awaitEachGesture
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
                // One scroll session for the whole drag, at the priority a finger on the list gets.
                // A long press usually lands while the list is still coasting, and touching a
                // coasting list starts the list's own drag at once, to catch it -- a drag that holds
                // the list at UserInput until the finger lifts. Each move used to be a
                // `scrollToItem`, at the lower Default priority, and every one of them was cancelled
                // against that drag: the thumb took the accent colour and the list never moved.
                // Taken at UserInput, this session is the one that wins.
                val targets = Channel<Pair<Int, Int>>(Channel.CONFLATED)
                scope.launch {
                    try {
                        state.scroll(MutatePriority.UserInput) {
                            val rows = LazyLayoutScrollScope(state, this)
                            for ((index, offset) in targets) rows.snapToItem(index, offset)
                        }
                    } finally {
                        // After the last jump has landed, so it sees where the list really ended up.
                        latestOnStopped()
                    }
                }
                try {
                    followFinger(down) { y ->
                        val info = state.layoutInfo
                        sizes.record(info)
                        val trackTop = latestTopInset().coerceIn(0f, height)
                        val track = height - trackTop
                        val thumb = lazyListScrollbarThumb(info, state.canScrollBackward, state.canScrollForward, sizeOf)
                            ?: return@followFinger
                        val fraction = fastScrollFraction(y, trackTop, track, thumbHeightPx(track, thumb.size))
                        targets.trySend(fastScrollTarget(fraction, info, sizeOf))
                    }
                } finally {
                    targets.close()
                    fastScrolling = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            thumbOf(size.height)?.let { drawThumb(it, look) }
        }
        .thumbGestureExclusion { height ->
            if (look.alpha.value <= 0f || fastScrolling || state.isScrollInProgress) null else thumbOf(height)
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
 * How far a held finger may wander, in touch slops, and still be holding. A pad pressed flat against
 * the edge of the screen rolls as it settles, and one slop -- what a tap or a scroll goes by -- is
 * less than that roll: a press the person meant as perfectly still was read as the start of a
 * scroll, and the thumb never took. A scroll the list could lose to this has to cover under two
 * slops in the whole long-press timeout, slower than anyone reads.
 */
private const val LongPressSlops = 2f

/**
 * Waits out a long press without claiming anything. False as soon as the finger lifts, wanders
 * past [LongPressSlops], or something else takes the event -- a tap or a scroll that merely started
 * on the edge, which the list then handles exactly as it would have without this.
 */
private suspend fun AwaitPointerEventScope.awaitLongPressInPlace(down: PointerInputChange): Boolean {
    val slop = viewConfiguration.touchSlop * LongPressSlops
    val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var gaveUp = false
        while (!gaveUp) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            gaveUp = change == null || !change.pressed || change.isConsumed ||
                (change.position - down.position).getDistance() > slop
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
        // Four seconds to go: held for two, then faded over the next two. The hold is what leaves
        // time to reach for it and long-press it -- a slower fade alone spends most of its change
        // in the first few frames, so the thumb looked gone almost as soon.
        animationSpec = if (shown) tween(durationMillis = 150) else tween(durationMillis = 2_000, delayMillis = 2_000),
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

/** Where a thumb is drawn, in px down its container: its top edge and its height. */
internal class ThumbSpan(val top: Float, val height: Float)

internal fun thumbSpan(thumb: ScrollbarThumb, trackTop: Float, height: Float): ThumbSpan? {
    val track = height - trackTop
    if (track <= 0f) return null
    val thumbHeight = thumbHeightPx(track, thumb.size)
    return ThumbSpan(trackTop + thumb.offset * (track - thumbHeight), thumbHeight)
}

private fun ContentDrawScope.drawThumb(thumb: ThumbSpan, look: ScrollbarLook) {
    val alpha = look.alpha.value
    if (alpha <= 0f) return
    val width = look.width.value.toPx()
    val inset = ScrollbarInset.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) inset else size.width - width - inset
    drawRoundRect(
        color = look.color.copy(alpha = look.color.alpha * alpha),
        topLeft = Offset(x, thumb.top),
        size = Size(width, thumb.height),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}

/**
 * The stretch of edge kept from the system's own gestures while the thumb shows, in the container's
 * px: the whole touch zone across, and down the thumb with half the zone's width to spare above
 * and below, so a press just off its end still lands in it.
 */
internal fun thumbExclusionBounds(
    thumb: ThumbSpan,
    width: Float,
    height: Float,
    touchWidth: Float,
    direction: LayoutDirection,
): Rect {
    val left = if (direction == LayoutDirection.Rtl) 0f else width - touchWidth
    val spare = touchWidth / 2f
    return Rect(
        left = left.coerceAtLeast(0f),
        top = (thumb.top - spare).coerceAtLeast(0f),
        right = (left + touchWidth).coerceAtMost(width),
        bottom = (thumb.top + thumb.height + spare).coerceAtMost(height),
    )
}

/**
 * Keeps the system's edge gestures off the thumb for as long as [thumb] returns one.
 *
 * A thumb on the very edge of the screen is sitting in the back gesture's strip. Stock Android
 * still hands the app a finger held still there, but HyperOS does not: it lays a window of its own
 * over the strip that takes every touch, and passes one on only when it lands inside what the app
 * has declared an exclusion -- which is what this declares.
 *
 * Compose's own `systemGestureExclusion` works out its rect only when the node moves, and a list
 * scrolling does not move the list, only the thumb inside it. This node follows [thumb]'s reads
 * instead, and leaves any rect declared by anything else on the view where it was.
 */
private fun Modifier.thumbGestureExclusion(thumb: (height: Float) -> ThumbSpan?): Modifier =
    this then ThumbExclusionElement(thumb)

private class ThumbExclusionElement(val thumb: (Float) -> ThumbSpan?) : ModifierNodeElement<ThumbExclusionNode>() {
    override fun create() = ThumbExclusionNode(thumb)

    override fun update(node: ThumbExclusionNode) {
        node.thumb = thumb
        node.refresh()
    }

    override fun equals(other: Any?) = other is ThumbExclusionElement && other.thumb === thumb

    override fun hashCode() = thumb.hashCode()
}

private class ThumbExclusionNode(var thumb: (Float) -> ThumbSpan?) :
    Modifier.Node(), GlobalPositionAwareModifierNode, ObserverModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var excluded: android.graphics.Rect? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        refresh()
    }

    override fun onObservedReadsChanged() = refresh()

    override fun onDetach() {
        replace(null)
        coordinates = null
    }

    fun refresh() {
        if (!isAttached) return
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return
        val width = coordinates.size.width.toFloat()
        val height = coordinates.size.height.toFloat()
        var span: ThumbSpan? = null
        observeReads { span = thumb(height) }
        val local = span?.let {
            val touch = with(requireDensity()) { FastScrollTouchWidth.toPx() }
            thumbExclusionBounds(it, width, height, touch, requireLayoutDirection())
        }
        replace(local?.let { inView(coordinates, it) })
    }

    private fun inView(coordinates: LayoutCoordinates, local: Rect): android.graphics.Rect {
        val root = coordinates.findRootCoordinates()
        val topLeft = root.localPositionOf(coordinates, local.topLeft)
        val bottomRight = root.localPositionOf(coordinates, local.bottomRight)
        return android.graphics.Rect(
            minOf(topLeft.x, bottomRight.x).roundToInt(),
            minOf(topLeft.y, bottomRight.y).roundToInt(),
            maxOf(topLeft.x, bottomRight.x).roundToInt(),
            maxOf(topLeft.y, bottomRight.y).roundToInt(),
        )
    }

    /** Swaps this node's rect in the view's list, and only when it actually changed: each set is IPC. */
    private fun replace(rect: android.graphics.Rect?) {
        if (rect == excluded) return
        val view = requireView()
        val rects = view.systemGestureExclusionRects.toMutableList()
        excluded?.let { rects.remove(it) }
        if (rect != null && !rect.isEmpty) rects += rect
        view.systemGestureExclusionRects = rects
        excluded = rect
    }
}
