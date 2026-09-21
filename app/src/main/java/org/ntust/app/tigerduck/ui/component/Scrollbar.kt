package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val ScrollbarWidth = 3.dp
private val ScrollbarInset = 2.dp
private const val MinThumbPx = 40f

/**
 * Where the thumb sits, as fractions of its track: [offset] 0 at the top and 1 at the bottom of
 * its travel, [size] the share of the track it covers.
 */
internal data class ScrollbarThumb(val offset: Float, val size: Float)

/**
 * A thin scroll indicator on the trailing edge of a [verticalScroll][androidx.compose.foundation.verticalScroll]
 * container. It shows while the content moves and fades once it stops, and never appears for
 * content that fits.
 */
@Composable
fun Modifier.scrollbar(state: ScrollState): Modifier {
    val alpha = scrollbarAlpha(state.isScrollInProgress)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    return drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0) return@drawWithContent
        val viewport = size.height
        drawThumb(ScrollbarThumb(state.value.toFloat() / max, viewport / (viewport + max)), 0f, color, alpha.value)
    }
}

/**
 * The same indicator for a `LazyColumn`, whose full height is never measured: it is estimated
 * from the rows on screen, which is close enough for a thumb that only says where you are.
 *
 * [topInsetPx] keeps the track clear of anything drawn over the top of the list -- a page's
 * chrome overlay, which would otherwise hide the thumb whenever the list is near its top. Read
 * at draw time, so a moving chrome only redraws the thumb.
 */
@Composable
fun Modifier.scrollbar(state: LazyListState, topInsetPx: () -> Float = { 0f }): Modifier {
    val alpha = scrollbarAlpha(state.isScrollInProgress)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    return drawWithContent {
        drawContent()
        val thumb = lazyListScrollbarThumb(
            state.layoutInfo,
            canScrollBackward = state.canScrollBackward,
            canScrollForward = state.canScrollForward,
        ) ?: return@drawWithContent
        drawThumb(thumb, topInsetPx().coerceIn(0f, size.height), color, alpha.value)
    }
}

/**
 * Estimates the thumb from the visible rows: their average height, spacing included, stands in
 * for every row the list has not measured. Null when the list fits and there is nothing to show.
 *
 * The two ends are taken from the list rather than the estimate, so the thumb reaches the very
 * top and bottom of its track exactly when the list does, however uneven the rows are.
 */
internal fun lazyListScrollbarThumb(
    info: LazyListLayoutInfo,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): ScrollbarThumb? {
    if (!canScrollBackward && !canScrollForward) return null
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || info.totalItemsCount <= 0) return null
    val first = visible.first()
    val last = visible.last()
    val averageItem = (last.offset + last.size - first.offset).toFloat() / visible.size
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val content = info.beforeContentPadding + averageItem * info.totalItemsCount + info.afterContentPadding
    val maxScroll = content - viewport
    if (maxScroll <= 0f || viewport <= 0f) return null
    val scrolled = info.beforeContentPadding + first.index * averageItem - first.offset + info.viewportStartOffset
    val offset = when {
        !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> (scrolled / maxScroll).coerceIn(0f, 1f)
    }
    return ScrollbarThumb(offset = offset, size = (viewport / content).coerceIn(0f, 1f))
}

@Composable
private fun scrollbarAlpha(scrolling: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (scrolling) 1f else 0f,
    animationSpec = tween(durationMillis = if (scrolling) 150 else 500),
    label = "scrollbar_alpha",
)

private fun ContentDrawScope.drawThumb(thumb: ScrollbarThumb, trackTop: Float, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val track = size.height - trackTop
    if (track <= 0f) return
    val thumbHeight = (track * thumb.size).coerceIn(minOf(MinThumbPx, track), track)
    val width = ScrollbarWidth.toPx()
    val inset = ScrollbarInset.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) inset else size.width - width - inset
    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(x, trackTop + thumb.offset * (track - thumbHeight)),
        size = Size(width, thumbHeight),
        cornerRadius = CornerRadius(width / 2f, width / 2f),
    )
}
