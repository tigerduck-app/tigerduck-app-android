package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.ntust.app.tigerduck.shared.AccentShader
import org.ntust.app.tigerduck.shared.meshTones
import org.ntust.app.tigerduck.shared.opaque
import org.ntust.app.tigerduck.shared.rampColors

/** What the watch's padding buttons and bars fill with, and the text on it. */
class AccentFill(val brush: Brush, val text: Color)

/**
 * The phone's [accent] as a fill, or with a [gradient] sent, the gradient laid across the fill in
 * its style, as [accentBrush] lays it across a mark.
 *
 * Every part is lifted until it reads on the watch's black ([readableOnBlack]): a single color as
 * it is, a gradient at points along it, every stop among them, since two colors that read can mix
 * in sRGB to a middle that doesn't, and a mesh at each of its nine colors. The text is black or
 * white, whichever reads better where it reads worst.
 */
fun accentFill(accent: Color, gradient: AccentGradient?): AccentFill {
    if (gradient == null || !gradient.isGradient) {
        val color = readableOnBlack(accent)
        return AccentFill(SolidColor(color), textOn(color))
    }
    val colors = if (gradient.style == AccentGradientStyle.Mesh && AccentShader.drawsMesh()) {
        gradient.meshTones(::lifted)
    } else {
        gradient.rampColors(::lifted, samples = points(gradient))
    }.map { Color(it) }
    val text = listOf(Color.Black, Color.White).maxBy { candidate -> colors.minOf { contrast(candidate, it) } }
    return AccentFill(LiftedBrush(gradient), text)
}

private fun lifted(rgb: Int): Int = readableOnBlack(Color(opaque(rgb))).toArgb()

/** [gradient] lifted to read on black, laid over whatever it fills. */
@Immutable
private class LiftedBrush(val gradient: AccentGradient) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        AccentShader.create(gradient, 0f, 0f, size.width, size.height, tone = ::lifted, samples = points(gradient))

    override fun equals(other: Any?): Boolean = other is LiftedBrush && other.gradient == gradient

    override fun hashCode(): Int = gradient.hashCode()
}

/**
 * Enough points along [gradient] that, made readable at each, it stays readable between them: eight
 * to each run from one stop to the next, so every stop is one of them. An angular gradient runs on
 * from its last stop back to its first.
 */
private fun points(gradient: AccentGradient): Int {
    val runs = if (gradient.style == AccentGradientStyle.Angular) gradient.stops.size else gradient.stops.size - 1
    return runs * POINTS_PER_RUN + 1
}

private const val POINTS_PER_RUN = 8
