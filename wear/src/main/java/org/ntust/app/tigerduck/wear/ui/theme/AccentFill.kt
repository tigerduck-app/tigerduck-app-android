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
import org.ntust.app.tigerduck.shared.meshAt
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
 * in sRGB to a middle that doesn't, and a mesh everywhere between its nine colors too
 * ([readableMeshColors]). The text is black or white, whichever reads better where it reads worst.
 */
fun accentFill(accent: Color, gradient: AccentGradient?): AccentFill {
    if (gradient == null || !gradient.isGradient) {
        val color = readableOnBlack(accent)
        return AccentFill(SolidColor(color), textOn(color))
    }
    val mesh = if (gradient.style == AccentGradientStyle.Mesh && AccentShader.drawsMesh()) readableMeshColors(gradient) else null
    val colors = (mesh?.let(::meshGrid) ?: gradient.rampColors(::lifted, samples = points(gradient))).map { Color(it) }
    val text = listOf(Color.Black, Color.White).maxBy { candidate -> colors.minOf { contrast(candidate, it) } }
    return AccentFill(LiftedBrush(gradient, mesh), text)
}

/**
 * The nine colors of [gradient]'s mesh, made to read on black at every point the mesh draws, not
 * only at the nine: each lifted on its own ([readableOnBlack]), and then all of them together
 * toward white, a step at a time, until every point of a fine grid across the mesh reads. A mesh
 * bends between its colors and can dip darker than any of them; lifting all nine alike lifts each
 * point between them alike, so the walk always ends, at white.
 */
internal fun readableMeshColors(gradient: AccentGradient): List<Int> {
    val own = gradient.meshTones(::lifted)
    var lift = 0f
    while (lift < 1f) {
        val nine = own.map { towardWhite(it, lift) }
        if (meshGrid(nine).all { contrast(Color(it), Color.Black) >= MIN_ON_BLACK }) return nine
        lift += LIFT_STEP
    }
    return List(9) { Color.White.toArgb() }
}

/** [argb] [fraction] of the way to white, each sRGB channel in a straight line, as the mesh mixes them. */
private fun towardWhite(argb: Int, fraction: Float): Int = listOf(16, 8, 0).fold(0xFF000000.toInt()) { color, shift ->
    val channel = (argb shr shift) and 0xFF
    color or (Math.round(channel + (0xFF - channel) * fraction).coerceIn(0, 0xFF) shl shift)
}

/** The mesh of [nine] colors at every point of a [MESH_GRID] by [MESH_GRID] grid across it. */
private fun meshGrid(nine: List<Int>): List<Int> =
    List(MESH_GRID * MESH_GRID) { meshAt(nine, (it % MESH_GRID) / (MESH_GRID - 1f), (it / MESH_GRID) / (MESH_GRID - 1f)) }

private const val MESH_GRID = 33
private const val MIN_ON_BLACK = 4.5f
private const val LIFT_STEP = 0.05f

private fun lifted(rgb: Int): Int = readableOnBlack(Color(opaque(rgb))).toArgb()

/** [gradient] lifted to read on black, a mesh through the nine colors [mesh], laid over whatever it fills. */
@Immutable
private class LiftedBrush(val gradient: AccentGradient, val mesh: List<Int>?) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        AccentShader.create(gradient, 0f, 0f, size.width, size.height, tone = ::lifted, samples = points(gradient), mesh = mesh)

    override fun equals(other: Any?): Boolean = other is LiftedBrush && other.gradient == gradient && other.mesh == mesh

    override fun hashCode(): Int = gradient.hashCode() * 31 + mesh.hashCode()
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
