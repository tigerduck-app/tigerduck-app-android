package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp

/** What the watch's padding buttons and bars fill with, and the text on it. */
class AccentFill(val brush: Brush, val text: Color)

/**
 * The phone's [accent] as a fill, or with a [gradient] picked, the gradient laid
 * across the fill in its direction, as [accentBrush] lays it across a mark.
 *
 * Every part is lifted until it reads on the watch's black ([readableOnBlack]):
 * a single color as it is, a gradient at stops along it, both ends among them,
 * since two ends that read can mix in sRGB to a middle that doesn't. The text
 * is black or white, whichever reads better where it reads worst.
 *
 * [mirrored] is for a canvas that is flipped left to right before it shows, as
 * Wear's progress indicator flips its own in a right-to-left language: the
 * gradient is laid the other way round, so it lands on the screen's own sides.
 */
fun accentFill(accent: Color, gradient: WearAccentGradient?, mirrored: Boolean = false): AccentFill {
    if (gradient == null) {
        val color = readableOnBlack(accent)
        return AccentFill(SolidColor(color), textOn(color))
    }
    val stops = List(GRADIENT_STOPS) { readableOnBlack(lerp(accent, gradient.end, it / (GRADIENT_STOPS - 1f))) }
    val direction = gradient.direction
    fun x(fraction: Float) = if (mirrored) 1f - fraction else fraction
    val brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader = LinearGradientShader(
            from = Offset(x(direction.startX) * size.width, direction.startY * size.height),
            to = Offset(x(direction.endX) * size.width, direction.endY * size.height),
            colors = stops,
        )
    }
    val text = listOf(Color.Black, Color.White).maxBy { candidate -> stops.minOf { contrast(candidate, it) } }
    return AccentFill(brush, text)
}

/** Enough stops that a gradient made readable at each stays readable between them. */
private const val GRADIENT_STOPS = 9
