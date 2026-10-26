package org.ntust.app.tigerduck.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.shared.AccentGradientDirection

/** The end color and direction of the accent gradient picked; its start is the accent color. */
@Immutable
data class AccentGradient(val end: Color, val direction: AccentGradientDirection)

/** Where a gradient along this direction starts and ends in a shape of [size]. */
internal fun AccentGradientDirection.endpoints(size: Size): Pair<Offset, Offset> =
    Offset(startX * size.width, startY * size.height) to Offset(endX * size.width, endY * size.height)

/**
 * The accent gradient from [start] to [end] along [direction], laid over
 * whatever shape it fills. A class of its own, and not a linear gradient made
 * once, so it stretches to each shape it fills and compares equal to an equal
 * gradient, which a recomposition then leaves alone.
 */
@Immutable
class AccentGradientBrush(
    val start: Color,
    val end: Color,
    val direction: AccentGradientDirection,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val (from, to) = direction.endpoints(size)
        return LinearGradientShader(from = from, to = to, colors = listOf(start, end))
    }

    override fun equals(other: Any?): Boolean =
        other is AccentGradientBrush && other.start == start && other.end == end && other.direction == direction

    override fun hashCode(): Int = (start.hashCode() * 31 + end.hashCode()) * 31 + direction.hashCode()

    override fun toString(): String = "AccentGradientBrush(start=$start, end=$end, direction=$direction)"
}

/** What the accent's own fills paint with; see [TigerDuckTheme.accentBrush]. */
internal val LocalAccentBrush = staticCompositionLocalOf<Brush> { SolidColor(LightColorScheme.primary) }

/** What text and marks on those fills draw in; see [TigerDuckTheme.onAccentBrush]. */
internal val LocalOnAccentBrush = staticCompositionLocalOf { LightColorScheme.onPrimary }

/** The light theme's text color, offered against white on a light fill. */
private val DarkText = Color(0xFF1C1C1E)

/**
 * What text and marks on a gradient from [start] to [end] draw in: of
 * [onStart], the text on its start, and its opposite, whichever reads best
 * across the gradient's middle. A fill's number or label sits centered, where
 * the gradient is half way, and two ends far apart, light yellow into black,
 * leave the start's text unreadable there.
 */
internal fun readableOnGradient(start: Color, end: Color, onStart: Color): Color {
    val other = if (onStart.luminance() > 0.5f) DarkText else Color.White
    val middle = listOf(0.35f, 0.5f, 0.65f).map { shaderMix(start, end, it) }
    fun worst(text: Color) = middle.minOf { AccentPolicy.contrast(text, it) }
    return if (worst(other) > worst(onStart)) other else onStart
}

/**
 * [start] [fraction] of the way to [end], as the gradient's shader draws it:
 * each sRGB component in a straight line, not Compose's perceptual [lerp],
 * whose middle comes out lighter than the one on screen.
 */
private fun shaderMix(start: Color, end: Color, fraction: Float) = Color(
    red = start.red + (end.red - start.red) * fraction,
    green = start.green + (end.green - start.green) * fraction,
    blue = start.blue + (end.blue - start.blue) * fraction,
)
