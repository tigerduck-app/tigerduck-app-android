package org.ntust.app.tigerduck.shared

/*
 * The colors an accent's fill shows, as [AccentShader] draws it, worked out without drawing: what
 * the watch lifts to read on black, and what text on the fill is chosen against. Every color is an
 * ARGB or RGB hex; [tone] turns a stop's RGB into the color drawn for it.
 */

/** [rgb] as an opaque ARGB color, the [tone] that draws a stop as it is. */
fun opaque(rgb: Int): Int = rgb or 0xFF000000.toInt()

/**
 * The colors a linear, radial or angular fill of this accent runs through, evenly spaced from its
 * start, as [tone] draws them: the stops themselves, or with [samples] that many points along them,
 * so a fill made of colors each lifted on its own (the watch's) stays lifted between them too. An
 * angular fill comes round to its first stop again, as the iPhone's does.
 */
fun AccentGradient.rampColors(tone: (Int) -> Int = ::opaque, samples: Int = 0): List<Int> {
    val toned = stops.map(tone)
    val run = if (style == AccentGradientStyle.Angular) toned + toned[0] else toned
    if (samples < 2) return if (run.size < 2) run + run else run
    return List(samples) { mix(run, it / (samples - 1f)) }
}

/** The 3 by 3 mesh's colors ([AccentGradient.meshColors]) as [tone] draws them. */
fun AccentGradient.meshTones(tone: (Int) -> Int = ::opaque): List<Int> = meshColors.map(tone)

/**
 * The colors around the middle of a fill of this accent, where its text and marks sit: half way
 * along a linear one, the center of a radial one, every color of an angular one, which all meet
 * there, and the middle of a mesh. Under [meshDrawn] false a mesh is drawn as its stand-in, a linear
 * gradient from the top left (see [AccentShader]), and its middle is that one's.
 */
fun AccentGradient.middleColors(tone: (Int) -> Int = ::opaque, meshDrawn: Boolean = true): List<Int> {
    if (!isGradient) return listOf(tone(first))
    val run = rampColors(tone)
    return when (style) {
        AccentGradientStyle.Linear -> listOf(0.35f, 0.5f, 0.65f).map { mix(run, it) }
        AccentGradientStyle.Radial -> listOf(0f, 0.15f, 0.3f).map { mix(run, it) }
        AccentGradientStyle.Angular -> run.zipWithNext { from, to -> listOf(from, blend(from, to, 0.5f)) }.flatten()
        AccentGradientStyle.Mesh -> if (meshDrawn) {
            val mesh = meshTones(tone)
            listOf(0.5f to 0.5f, 0.35f to 0.5f, 0.65f to 0.5f, 0.5f to 0.35f, 0.5f to 0.65f).map { (u, v) -> meshAt(mesh, u, v) }
        } else {
            listOf(0.35f, 0.5f, 0.65f).map { mix(run, it) }
        }
    }
}

/**
 * [colors], evenly spaced, at [fraction] of the way along them: each sRGB channel in a straight
 * line between its two neighbors, as a gradient shader mixes them.
 */
fun mix(colors: List<Int>, fraction: Float): Int {
    if (colors.size == 1) return colors[0]
    val at = fraction.coerceIn(0f, 1f) * (colors.size - 1)
    val index = at.toInt().coerceAtMost(colors.size - 2)
    return blend(colors[index], colors[index + 1], at - index)
}

/**
 * The mesh of the nine [colors], row by row from the top left, at [u] across and [v] down, each
 * from 0 to 1, as [AccentShader]'s mesh draws it: through every one of the nine, and smooth between
 * them, each axis a quadratic through its three, each channel kept between 0 and 255.
 */
fun meshAt(colors: List<Int>, u: Float, v: Float): Int {
    val across = quadratic(u)
    val down = quadratic(v)
    return listOf(24, 16, 8, 0).fold(0) { argb, shift ->
        var channel = 0f
        for (row in 0..2) for (column in 0..2) {
            channel += ((colors[row * 3 + column] ushr shift) and 0xFF) * across[column] * down[row]
        }
        argb or (Math.round(channel).coerceIn(0, 0xFF) shl shift)
    }
}

/** The weights a quadratic through three points evenly spaced on 0..1 gives each at [t]. */
internal fun quadratic(t: Float): FloatArray = floatArrayOf(
    2 * (t - 0.5f) * (t - 1),
    -4 * t * (t - 1),
    2 * t * (t - 0.5f),
)

private fun blend(a: Int, b: Int, t: Float): Int = listOf(24, 16, 8, 0).fold(0) { argb, shift ->
    val from = (a ushr shift) and 0xFF
    val to = (b ushr shift) and 0xFF
    argb or (Math.round(from + (to - from) * t).coerceIn(0, 0xFF) shl shift)
}

/** This accent with each stop as [tone] draws it, kept to its RGB: the accent as a mode or a surface draws it. */
fun AccentGradient.toned(tone: (Int) -> Int): AccentGradient = withStops(stops.map(tone))
