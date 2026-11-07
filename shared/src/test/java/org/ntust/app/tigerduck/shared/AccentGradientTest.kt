package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accent as the iPhone's `AccentGradient` holds it (tigerduck-app#1004): one to five stops
 * and a style, stored beside `accentColorHex`, which keeps its first stop.
 */
class AccentGradientTest {

    private val indigo = 0x5856D6

    private fun gradient(
        vararg stops: Int,
        style: AccentGradientStyle = AccentGradientStyle.Linear,
        direction: AccentGradientDirection = AccentGradientDirection.TopLeftToBottomRight,
    ): AccentGradient = requireNotNull(AccentGradient.of(stops.toList(), style, direction))

    @Test
    fun `one color is an accent without a gradient`() {
        val accent = AccentGradient.color(indigo)

        assertEquals(listOf(indigo), accent.stops)
        assertEquals(indigo, accent.first)
        assertFalse(accent.isGradient)
        assertEquals(AccentGradientStyle.Linear, accent.style)
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, accent.direction)
    }

    @Test
    fun `an accent has one to five stops`() {
        assertNull(AccentGradient.of(emptyList()))
        assertNull(AccentGradient.of(List(6) { 0x007AFF }))
        for (count in 1..5) {
            val accent = AccentGradient.of(List(count) { 0x007AFF })
            assertEquals(count, accent?.stops?.size)
            assertEquals(count > 1, accent?.isGradient)
        }
    }

    @Test
    fun `a change that leaves no stop or a sixth is undone`() {
        val five = gradient(0x111111, 0x222222, 0x333333, 0x444444, 0x555555)
        assertFalse(five.canAddStop)
        assertSame(five, five.adding(0x666666))

        val single = AccentGradient.color(0x111111)
        assertTrue(single.canAddStop)
        assertSame(single, single.removing(0))
        assertSame(single, single.withStops(emptyList()))
    }

    @Test
    fun `stops can be added, removed, moved and replaced`() {
        val accent = gradient(0x111111, 0x222222)
            .adding(0x333333)
            .moving(from = 2, to = 0)
            .replacing(1, 0x444444)
            .removing(0)

        assertEquals(listOf(0x444444, 0x222222), accent.stops)
    }

    @Test
    fun `a stop moved past either end, or a stop that is not there, changes nothing`() {
        val accent = gradient(0x111111, 0x222222, 0x333333)

        assertSame(accent, accent.moving(from = 0, to = 3))
        assertSame(accent, accent.moving(from = -1, to = 0))
        assertSame(accent, accent.replacing(3, 0x444444))
        assertSame(accent, accent.removing(3))
        assertEquals(listOf(0x222222, 0x333333, 0x111111), accent.moving(from = 0, to = 2).stops)
    }

    @Test
    fun `a stop keeps only its RGB`() {
        val accent = gradient(0xFF5856D6.toInt(), 0x007AFF).replacing(1, 0x7F34C759)

        assertEquals(listOf(0x5856D6, 0x34C759), accent.stops)
        assertEquals(indigo, AccentGradient.color(0xFF5856D6.toInt()).first)
    }

    @Test
    fun `there are four styles and eight directions, with the iPhone's names`() {
        assertEquals(
            listOf("linear", "radial", "angular", "mesh"),
            AccentGradientStyle.entries.map { it.wireName },
        )
        assertEquals(
            setOf(
                "leftToRight", "topLeftToBottomRight", "topToBottom", "topRightToBottomLeft",
                "rightToLeft", "bottomRightToTopLeft", "bottomToTop", "bottomLeftToTopRight",
            ),
            AccentGradientDirection.entries.map { it.wireName }.toSet(),
        )
    }

    @Test
    fun `every accent survives encoding, in every style and direction`() {
        for (style in AccentGradientStyle.entries) {
            for (direction in AccentGradientDirection.entries) {
                val accent = gradient(0x4B48D1, 0x9442C7, 0xD61F45, style = style, direction = direction)
                assertEquals(accent, AccentGradient.decode(accent.encode()))
            }
        }
    }

    @Test
    fun `it encodes as the iPhone's JSON`() {
        val accent = gradient(0x4B48D1, 0xD61F45, style = AccentGradientStyle.Mesh, direction = AccentGradientDirection.BottomToTop)

        assertEquals("""{"stops":[4933841,14032709],"style":"mesh","direction":"bottomToTop"}""", accent.encode())
        assertEquals(
            accent,
            AccentGradient.decode("""{"direction":"bottomToTop","style":"mesh","stops":[4933841,14032709]}"""),
        )
    }

    @Test
    fun `a stored value outside the rules, or one a newer build wrote, decodes as none`() {
        listOf(
            """{"stops":[],"style":"linear","direction":"leftToRight"}""",
            """{"stops":[1,2,3,4,5,6],"style":"linear","direction":"leftToRight"}""",
            """{"stops":[1,2],"style":"diamond","direction":"leftToRight"}""",
            """{"stops":[1,2],"style":"linear","direction":"sideways"}""",
            """{"stops":[1,2],"style":"linear"}""",
            """{"stops":[1,"2"],"style":"linear","direction":"leftToRight"}""",
            """{"stops":[1,2.5],"style":"linear","direction":"leftToRight"}""",
            """{"stops":1,"style":"linear","direction":"leftToRight"}""",
            """[1,2]""",
            """{"stops":[1,2],""",
            "",
            "null",
        ).forEach { json -> assertNull(json, AccentGradient.decode(json)) }
        assertNull(AccentGradient.decode(null))
    }

    @Test
    fun `a stop stored beyond RGB keeps its RGB, as the iPhone reads it`() {
        val decoded = AccentGradient.decode("""{"stops":[4294967295,1],"style":"linear","direction":"leftToRight"}""")

        assertEquals(listOf(0xFFFFFF, 1), decoded?.stops)
    }

    @Test
    fun `the accent from before gradients is its one color`() {
        assertEquals(AccentGradient.color(indigo), AccentGradient.saved(null, accentHex = indigo))
    }

    @Test
    fun `a gradient counts while its first stop is the saved accent`() {
        val stored = gradient(0x4B48D1, 0xD61F45, style = AccentGradientStyle.Mesh)

        assertEquals(stored, AccentGradient.saved(stored, accentHex = 0x4B48D1))
        assertEquals(stored, AccentGradient.saved(stored, accentHex = 0x7F4B48D1))
        // A color an older build picked, or one picked anywhere since, replaces it.
        assertEquals(AccentGradient.color(0x007AFF), AccentGradient.saved(stored, accentHex = 0x007AFF))
        assertEquals(
            AccentGradient.color(0x007AFF),
            AccentGradient.saved(AccentGradient.color(0x4B48D1), accentHex = 0x007AFF),
        )
    }

    @Test
    fun `the start, end and direction the previous release stored read as a two-stop linear gradient`() {
        val legacy = AccentGradient.legacy(startHex = 0x007AFF, endHex = 0xAF52DE, directionCode = 2)

        assertEquals(gradient(0x007AFF, 0xAF52DE, direction = AccentGradientDirection.LeftToRight), legacy)
        // A direction this build does not know runs as one picked before any direction was.
        assertEquals(
            AccentGradientDirection.TopLeftToBottomRight,
            AccentGradient.legacy(0x007AFF, 0xAF52DE, directionCode = 42)?.direction,
        )
        // -1, the release's "no gradient", and any other value that is no color, is none.
        assertNull(AccentGradient.legacy(0x007AFF, -1, directionCode = 5))
        assertNull(AccentGradient.legacy(-1, 0xAF52DE, directionCode = 5))
        assertNull(AccentGradient.legacy(0x007AFF, 0x1000000, directionCode = 5))
    }

    @Test
    fun `a mesh puts each stop on its spot`() {
        val four = gradient(0xFF0000, 0x00FF00, 0x0000FF, 0x000000, style = AccentGradientStyle.Mesh)
        val five = gradient(0xFF0000, 0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF, style = AccentGradientStyle.Mesh)

        assertEquals(9, four.meshColors.size)
        assertEquals(four.stops, listOf(four.meshColors[0], four.meshColors[2], four.meshColors[8], four.meshColors[6]))
        assertEquals(0xFFFFFF, five.meshColors[4])
        assertEquals(List(9) { indigo }, AccentGradient.color(indigo).meshColors)
    }

    @Test
    fun `a mesh blends between its spots`() {
        val two = gradient(0x000000, 0xFEFEFE, style = AccentGradientStyle.Mesh)

        assertEquals(0x000000, two.meshColors[0])
        assertEquals(0xFEFEFE, two.meshColors[8])
        assertEquals(0x7F7F7F, two.meshColors[4])
    }

    @Test
    fun `a style or direction picked keeps the stops`() {
        val accent = gradient(0x111111, 0x222222)
            .withStyle(AccentGradientStyle.Radial)
            .withDirection(AccentGradientDirection.BottomToTop)

        assertEquals(listOf(0x111111, 0x222222), accent.stops)
        assertEquals(AccentGradientStyle.Radial, accent.style)
        assertEquals(AccentGradientDirection.BottomToTop, accent.direction)
        assertNotNull(AccentGradientDirection.fromWireName("bottomToTop"))
        assertNull(AccentGradientDirection.fromWireName("sideways"))
    }
}
