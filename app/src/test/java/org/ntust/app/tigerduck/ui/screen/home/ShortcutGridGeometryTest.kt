package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slot a dragged shortcut card lands on. A 2-column grid of 5 cards,
 * cells 100px wide and 80px tall with a 12px gap, so one column is 112px
 * across and one row 92px down:
 *
 *     0 1
 *     2 3
 *     4
 */
class ShortcutGridGeometryTest {

    private fun target(from: Int, dx: Float, dy: Float, count: Int = 5) =
        ShortcutGridGeometry.targetIndex(
            from = from, count = count, columns = 2,
            dx = dx, dy = dy, pitchX = 112f, pitchY = 92f,
        )

    @Test
    fun `a card that has not moved half a cell stays put`() {
        assertEquals(0, target(0, dx = 55f, dy = 0f))
        assertEquals(0, target(0, dx = 0f, dy = 45f))
        assertEquals(3, target(3, dx = -55f, dy = -45f))
    }

    @Test
    fun `crossing half a column moves one column over`() {
        // The same threshold as section reorder: half of the neighbour.
        assertEquals(1, target(0, dx = 57f, dy = 0f))
        assertEquals(2, target(3, dx = -57f, dy = 0f))
    }

    @Test
    fun `crossing half a row moves one row down or up`() {
        assertEquals(2, target(0, dx = 0f, dy = 47f))
        assertEquals(1, target(3, dx = 0f, dy = -47f))
        assertEquals(4, target(0, dx = 0f, dy = 2 * 92f))
    }

    @Test
    fun `a diagonal drag moves on both axes`() {
        assertEquals(3, target(0, dx = 112f, dy = 92f))
        assertEquals(0, target(3, dx = -112f, dy = -92f))
    }

    @Test
    fun `dragging past an edge lands on the edge`() {
        assertEquals(0, target(1, dx = -500f, dy = 0f))
        assertEquals(1, target(0, dx = 500f, dy = 0f))
        assertEquals(4, target(0, dx = 0f, dy = 900f))
        assertEquals(0, target(4, dx = 0f, dy = -900f))
    }

    @Test
    fun `an empty cell in the last row resolves to the last card`() {
        // Slot 5 does not exist; a drag that would land there takes slot 4.
        assertEquals(4, target(1, dx = 0f, dy = 2 * 92f))
        assertEquals(4, target(3, dx = 0f, dy = 92f))
    }

    @Test
    fun `an index outside the grid is handed back unchanged`() {
        assertEquals(7, target(7, dx = 0f, dy = 0f))
        assertEquals(0, target(0, dx = 100f, dy = 100f, count = 0))
    }
}
