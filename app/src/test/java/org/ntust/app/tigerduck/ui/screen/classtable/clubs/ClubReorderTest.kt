package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import org.junit.Assert.assertEquals
import org.junit.Test

/** A drag's new order over the subscriptions' own, as the iPhone's `ClubSubscribeSheet.move`. */
class ClubReorderTest {

    @Test
    fun `a subscribed club the directory no longer lists keeps its place among the moved ones`() {
        // 9002 is subscribed but not listed, so the sheet shows 9001, 9003, 9004.
        val order = listOf(9001, 9002, 9003, 9004)

        assertEquals(listOf(9004, 9002, 9001, 9003), reorderedOrder(order, shown = listOf(9004, 9001, 9003)))
        assertEquals(order, reorderedOrder(order, shown = listOf(9001, 9003, 9004)))
    }
}
