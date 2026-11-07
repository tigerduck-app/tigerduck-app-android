package org.ntust.app.tigerduck.wear.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.wear.ui.theme.accentBrush

/**
 * The library pass countdown ring's brush, kept for the accent, gradient and bounds it was made
 * for. The ring animates through every frame of its countdown, and a brush is a shader made once:
 * a new brush each frame would build a mesh's runtime shader again each frame. Its progress is no
 * part of it, so it stays the same brush while the ring turns.
 */
internal class CountdownRingBrush {
    private var madeFor: Triple<Color, AccentGradient?, Rect>? = null
    private var brush: Brush? = null

    fun of(accent: Color, gradient: AccentGradient?, bounds: Rect): Brush {
        val key = Triple(accent, gradient, bounds)
        brush?.takeIf { madeFor == key }?.let { return it }
        return accentBrush(accent, gradient, bounds).also {
            brush = it
            madeFor = key
        }
    }
}

/**
 * The countdown's ring: [progress] of it in [brush], the accent, from the top and clockwise, over
 * a faint track of the accent all the way round.
 */
internal fun DrawScope.drawCountdownRing(progress: Float, brush: Brush, strokePx: Float) {
    drawCircle(brush = brush, alpha = 0.18f, style = Stroke(width = strokePx))
    drawArc(
        brush = brush,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        style = Stroke(width = strokePx),
    )
}
