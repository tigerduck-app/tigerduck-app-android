package org.ntust.app.tigerduck.ui.screen.files

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

/** How far a viewer's content is zoomed in, and where it has been moved to. */
@Stable
internal class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * Zooms by [zoom] about [centroid], where the fingers were, and moves by [pan], the opposite
     * of how far they went; kept within [size]'s edges.
     */
    fun change(zoom: Float, centroid: Offset, pan: Offset, size: IntSize) {
        // A gesture's last event, with no finger left, has no centroid.
        if (!centroid.isSpecified || !centroid.x.isFinite() || !centroid.y.isFinite()) return
        if (!zoom.isFinite() || !pan.x.isFinite() || !pan.y.isFinite()) return
        val newScale = (scale * zoom).coerceIn(MIN, MAX)
        // Keep the point under the fingers where it is.
        val moved = (offset + centroid) * (newScale / scale) - centroid + pan
        scale = newScale
        offset = bounded(moved, size)
    }

    fun toggle(at: Offset, size: IntSize) {
        if (scale > MIN) {
            scale = MIN
            offset = Offset.Zero
        } else {
            change(DOUBLE_TAP / scale, at, Offset.Zero, size)
        }
    }

    private fun bounded(offset: Offset, size: IntSize): Offset {
        val maxX = size.width * (scale - 1f)
        val maxY = size.height * (scale - 1f)
        return Offset(offset.x.coerceIn(0f, maxX), offset.y.coerceIn(0f, maxY))
    }

    companion object {
        const val MIN = 1f
        const val MAX = 5f
        const val DOUBLE_TAP = 2.5f
    }
}

/**
 * Pinch to zoom and a double tap to zoom in or back out. One finger moves the zoomed content
 * when [oneFingerPans]; otherwise only two fingers do, leaving one finger to a list's scroll.
 */
internal fun Modifier.zoomable(state: ZoomState, oneFingerPans: Boolean): Modifier = this
    .pointerInput(state, oneFingerPans) {
        // On the Initial pass, ahead of a list inside: a pinch zooms, it doesn't also scroll.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                val zooming = pressed >= 2
                if (pressed > 0 && (zooming || (oneFingerPans && state.scale > ZoomState.MIN))) {
                    // Where the fingers were: the move is applied on top of it.
                    state.change(event.calculateZoom(), event.calculateCentroid(useCurrent = false), -event.calculatePan(), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.toggle(it, size) }) }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        transformOrigin = TransformOrigin(0f, 0f)
        translationX = -state.offset.x
        translationY = -state.offset.y
    }

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged() = position != previousPosition
