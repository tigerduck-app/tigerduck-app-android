package org.ntust.app.tigerduck.ui.screen

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * What a tab page's header has always been, for each page's own test to pin: a title 16dp in, in
 * a row at least 48dp tall under 16dp of padding, with what follows it ending 16dp from the edge.
 */
internal object TabHeaderChecks {
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    /** The title's node: a page may also say its own name lower down. */
    fun ComposeTestRule.title(title: String): SemanticsNodeInteraction = onAllNodesWithText(title).onFirst()

    /** [rowHeight] is the 48dp minimum, unless something in the row stands taller. */
    fun ComposeTestRule.assertHeaderAtTop(title: String, density: Float, rowHeight: Float = 48f) {
        val shown = bounds(title(title))
        assertEquals("the title's start", 16f * density, shown.left, 1f)
        assertEquals("the middle of the title's row", (16f + rowHeight / 2) * density, shown.center.y, 1f)
    }

    /**
     * [last] is what ends the row, whichever part of the header that is on this page.
     * [targetMargin] is an icon button's 4dp, between its 40dp button and its 48dp touch target.
     */
    fun ComposeTestRule.assertRowEndsWith(last: SemanticsNodeInteraction, density: Float, targetMargin: Float = 0f) {
        val edge = bounds(onRoot()).right
        assertEquals("the row's end", edge - (16f + targetMargin) * density, bounds(last).right, 1f)
    }

    fun ComposeTestRule.assertSitsLeftOf(first: SemanticsNodeInteraction, second: SemanticsNodeInteraction) {
        assertTrue("out of order in the row", bounds(first).right <= bounds(second).left + 1f)
    }

    /** One swipe up takes the title with the content, as a pinned bar's would stay. */
    fun ComposeTestRule.assertHeaderScrollsAway(title: String, density: Float) {
        onRoot().performTouchInput { swipeUp() }
        waitForIdle()
        val left = onAllNodesWithText(title).fetchSemanticsNodes()
        val atTop = left.any { kotlin.math.abs(it.boundsInRoot.center.y - 40f * density) < 1f }
        assertTrue("the title stayed put on a swipe", !atTop)
    }
}
