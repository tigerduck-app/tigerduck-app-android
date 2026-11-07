package org.ntust.app.tigerduck.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentShader
import org.ntust.app.tigerduck.shared.middleColors
import org.ntust.app.tigerduck.shared.opaque
import org.ntust.app.tigerduck.shared.toned

/**
 * The accent [drawn], a gradient whose stops are the colors a screen draws, laid over whatever
 * shape it fills in its style ([AccentShader], the one home for that). A class of its own, and not
 * a shader made once, so it stretches to each shape it fills and compares equal to an equal
 * gradient, which a recomposition then leaves alone.
 */
@Immutable
class AccentGradientBrush(val drawn: AccentGradient) : ShaderBrush() {
    override fun createShader(size: Size): Shader = AccentShader.create(drawn, 0f, 0f, size.width, size.height)

    override fun equals(other: Any?): Boolean = other is AccentGradientBrush && other.drawn == drawn

    override fun hashCode(): Int = drawn.hashCode()

    override fun toString(): String = "AccentGradientBrush($drawn)"
}

/**
 * This accent as a fill, each stop as [tone] draws it (an RGB or ARGB hex, its alpha ignored): one
 * color as itself, and a gradient as [AccentGradientBrush].
 */
fun AccentGradient.brush(tone: (Int) -> Int): Brush {
    val drawn = toned(tone)
    return if (drawn.isGradient) AccentGradientBrush(drawn) else SolidColor(Color(opaque(drawn.first)))
}

/** What the accent's own fills paint with; see [TigerDuckTheme.accentBrush]. */
internal val LocalAccentBrush = staticCompositionLocalOf<Brush> { SolidColor(LightColorScheme.primary) }

/** What text and marks on those fills draw in; see [TigerDuckTheme.onAccentBrush]. */
internal val LocalOnAccentBrush = staticCompositionLocalOf { LightColorScheme.onPrimary }

/** The light theme's text color, offered against white on a light fill. */
private val DarkText = Color(0xFF1C1C1E)

/**
 * What text and marks on a fill of [drawn] draw in: of [onFirst], the text on its first color, and
 * its opposite, whichever reads best around the fill's middle as the shader draws it there, in its
 * style ([middleColors]). A fill's number or label sits centered, and a gradient whose colors are
 * far apart, light yellow into black, leaves the first color's text unreadable there.
 */
internal fun readableOnGradient(
    drawn: AccentGradient,
    onFirst: Color,
    meshDrawn: Boolean = AccentShader.drawsMesh(),
): Color {
    val other = if (onFirst.luminance() > 0.5f) DarkText else Color.White
    val middle = drawn.middleColors(meshDrawn = meshDrawn).map { Color(it) }
    fun worst(text: Color) = middle.minOf { AccentPolicy.contrast(text, it) }
    return if (worst(other) > worst(onFirst)) other else onFirst
}
