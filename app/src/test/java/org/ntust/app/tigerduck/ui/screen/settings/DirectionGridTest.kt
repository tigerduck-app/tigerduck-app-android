package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** [directionGrid]: four direction tiles to a row, in pixels at 1x. */
class DirectionGridTest {

    private fun grid(width: Int) = directionGrid(widthPx = width, tilePx = 48, minGapPx = 4, maxGapPx = 12)

    @Test
    fun `with room to spare the tiles keep their size and the swatches' gap`() {
        assertEquals(DirectionGrid(tilePx = 48, gapPx = 12), grid(296))
    }

    // A 360dp phone at the app's 130% display size: 277dp, less the card's margins.
    @Test
    fun `a narrower card narrows the gap first`() {
        assertEquals(DirectionGrid(tilePx = 48, gapPx = 7), grid(213))
        assertEquals(DirectionGrid(tilePx = 48, gapPx = 4), grid(204))
    }

    @Test
    fun `only a card too narrow for four whole tiles shrinks them`() {
        assertEquals(DirectionGrid(tilePx = 42, gapPx = 4), grid(182))
    }

    @Test
    fun `four tiles and their gaps never run past the width`() {
        (1..400).forEach { width ->
            val g = grid(width)
            assertEquals("at $width", true, g.tilePx * 4 + g.gapPx * 3 <= width || g.tilePx == 1)
        }
    }

    @Test
    fun `an unbounded width keeps the tiles and the swatches' gap`() {
        assertEquals(DirectionGrid(tilePx = 48, gapPx = 12), grid(Int.MAX_VALUE))
    }
}
