package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colors an accent's fill shows, worked out without drawing it. */
class AccentColorsTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun gradient(vararg stops: Int, style: AccentGradientStyle = AccentGradientStyle.Linear) =
        requireNotNull(AccentGradient.of(stops.toList(), style))

    @Test
    fun `a fill runs through its stops as drawn, and an angular one comes round to its first`() {
        val tone = { rgb: Int -> opaque(rgb xor 0x000001) }

        assertEquals(listOf(red, blue), gradient(0xFF0000, 0x0000FF).rampColors())
        assertEquals(listOf(opaque(0xFF0001), opaque(0x0000FE)), gradient(0xFF0000, 0x0000FF).rampColors(tone))
        assertEquals(
            listOf(red, blue, red),
            gradient(0xFF0000, 0x0000FF, style = AccentGradientStyle.Angular).rampColors(),
        )
    }

    @Test
    fun `sampled, a fill keeps its ends and has the points between them`() {
        val ramp = gradient(0x000000, 0xFFFFFF).rampColors(samples = 9)

        assertEquals(9, ramp.size)
        assertEquals(black, ramp.first())
        assertEquals(white, ramp.last())
        assertEquals(opaque(0x808080), ramp[4])
    }

    // The watch lifts each point to read on black: a point mixed from two lifted stops is not lifted.
    @Test
    fun `sampled, each point is mixed first and toned after`() {
        val grayToRed = { rgb: Int -> if (rgb == 0x808080) red else opaque(rgb) }

        assertEquals(listOf(black, red, white), gradient(0x000000, 0xFFFFFF).rampColors(grayToRed, samples = 3))
    }

    @Test
    fun `mixing goes straight between neighbors, in sRGB`() {
        val three = listOf(black, white, black)

        assertEquals(black, mix(three, 0f))
        assertEquals(white, mix(three, 0.5f))
        assertEquals(opaque(0x808080), mix(three, 0.25f))
        assertEquals(black, mix(three, 1f))
        assertEquals(black, mix(three, 7f))
    }

    @Test
    fun `the mesh passes through each of its nine colors`() {
        val nine = List(9) { opaque(it * 0x1C1C1C) }

        for (row in 0..2) for (column in 0..2) {
            assertEquals(nine[row * 3 + column], meshAt(nine, column / 2f, row / 2f))
        }
    }

    @Test
    fun `the mesh between its colors stays a color`() {
        val corners = listOf(white, black, white, black, white, black, white, black, white)

        for (u in listOf(0.1f, 0.25f, 0.4f, 0.6f, 0.9f)) for (v in listOf(0.1f, 0.3f, 0.7f)) {
            val argb = meshAt(corners, u, v)
            assertEquals(0xFF, argb ushr 24)
        }
    }

    @Test
    fun `text sits where each style is half way`() {
        val linear = gradient(0x000000, 0xFFFFFF).middleColors()
        assertEquals(listOf(opaque(0x595959), opaque(0x808080), opaque(0xA6A6A6)), linear)

        // A radial fill's middle is its first color.
        assertEquals(black, gradient(0x000000, 0xFFFFFF, style = AccentGradientStyle.Radial).middleColors().first())

        // Every color of an angular fill meets in its middle.
        val angular = gradient(0xFF0000, 0x00FF00, 0x0000FF, style = AccentGradientStyle.Angular).middleColors()
        assertTrue(listOf(red, opaque(0x00FF00), blue).all { it in angular })

        // A mesh's middle is its fifth stop's spot, and its stand-in's half way along it.
        val mesh = gradient(0xFF0000, 0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF, style = AccentGradientStyle.Mesh)
        assertEquals(white, mesh.middleColors().first())
        assertEquals(mix(mesh.rampColors(), 0.5f), mesh.middleColors(meshDrawn = false)[1])
    }

    @Test
    fun `a single color's middle is itself`() {
        assertEquals(listOf(opaque(0x5856D6)), AccentGradient.color(0x5856D6).middleColors())
        assertEquals(listOf(opaque(0x5856D6), opaque(0x5856D6)), AccentGradient.color(0x5856D6).rampColors())
    }

    @Test
    fun `only Android 13 and later draw a mesh`() {
        assertEquals(false, AccentShader.drawsMesh(32))
        assertEquals(true, AccentShader.drawsMesh(33))
    }
}
