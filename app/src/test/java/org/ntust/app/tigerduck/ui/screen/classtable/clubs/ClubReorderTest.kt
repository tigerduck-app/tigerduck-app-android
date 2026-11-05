package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A drag's new order over the subscriptions' own, as the iPhone's `ClubSubscribeSheet.move`: the
 * dragged club moves next to the shown club it lands by, and a subscribed club the directory no
 * longer lists, which the sheet leaves out, stays where it stands among the others.
 */
class ClubReorderTest {

    // 999 is subscribed but not listed, so the sheet shows 101, 102 and 103.
    private val order = listOf(101, 999, 102, 103)

    @Test
    fun `a club dragged up goes in front of the shown club it lands before, and a hidden one keeps its place`() {
        assertEquals(listOf(103, 101, 999, 102), reorderedOrder(order, shown = listOf(103, 101, 102), moved = 103))
        assertEquals(listOf(101, 999, 103, 102), reorderedOrder(order, shown = listOf(101, 103, 102), moved = 103))
    }

    @Test
    fun `a club dragged down goes in front of the shown club after where it lands, or last`() {
        assertEquals(listOf(999, 102, 101, 103), reorderedOrder(order, shown = listOf(102, 101, 103), moved = 101))
        assertEquals(listOf(999, 102, 103, 101), reorderedOrder(order, shown = listOf(102, 103, 101), moved = 101))
    }

    @Test
    fun `an order the drag left as it was stays`() {
        assertEquals(order, reorderedOrder(order, shown = listOf(101, 102, 103), moved = 102))
    }
}
