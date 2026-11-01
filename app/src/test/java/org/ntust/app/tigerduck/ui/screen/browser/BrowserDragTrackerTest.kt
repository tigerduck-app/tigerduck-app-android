package org.ntust.app.tigerduck.ui.screen.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which way the finger moves the page, as the browser's bars read it; y grows down the screen. */
class BrowserDragTrackerTest {

    /** What the tracker told: true for the page dragged down, toward its end. */
    private val told = mutableListOf<Boolean>()
    private val drags = BrowserDragTracker(touchSlop = 8f, minFlingVelocity = 50f) { down -> told += down }

    @Test
    fun `a finger going up past the slop drags the page down`() {
        drags.down(1_000f)
        drags.moved(995f)
        assertEquals(emptyList<Boolean>(), told)
        drags.moved(990f)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a tap tells nothing`() {
        drags.down(1_000f)
        drags.moved(1_003f)
        drags.up(0f)
        assertEquals(emptyList<Boolean>(), told)
    }

    @Test
    fun `once the page is dragging, a turn back shorter than the slop is told`() {
        drags.down(1_000f)
        drags.moved(990f)
        drags.moved(986f)
        // Back down the screen by 6 px, under the slop: the page now goes up.
        drags.moved(992f)
        assertEquals(false, told.last())
    }

    @Test
    fun `a lift tells the way the page flings, though the last move went the other way`() {
        drags.down(1_000f)
        for (y in listOf(968f, 936f, 904f, 872f, 840f, 846f)) drags.moved(y)
        assertEquals("the last move went up the page", false, told.last())
        // Released going up the screen: the page flings down.
        drags.up(-1_500f)
        assertEquals(true, told.last())
    }

    @Test
    fun `a lift going down the screen flings the page up`() {
        drags.down(1_000f)
        for (y in listOf(1_032f, 1_064f, 1_054f)) drags.moved(y)
        drags.up(900f)
        assertEquals(false, told.last())
    }

    @Test
    fun `a lift under the fling velocity tells nothing more`() {
        drags.down(1_000f)
        drags.moved(990f)
        drags.moved(1_000f)
        told.clear()
        drags.up(-40f)
        drags.down(1_000f)
        drags.moved(990f)
        drags.up(40f)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a lift that never dragged the page flings nothing`() {
        drags.down(1_000f)
        drags.moved(1_004f)
        drags.up(-3_000f)
        assertEquals(emptyList<Boolean>(), told)
    }

    @Test
    fun `a touch taken away tells no fling`() {
        drags.down(1_000f)
        drags.moved(968f)
        told.clear()
        drags.cancel()
        assertEquals(emptyList<Boolean>(), told)
    }

    @Test
    fun `the fingers' moves are told with a second finger down and after it lifts`() {
        drags.down(1_000f)
        drags.moved(980f)
        // A second finger lands at 800: the pair's middle is at 890.
        drags.pointersChanged(890f)
        drags.moved(910f)
        assertEquals(false, told.last())
        // It lifts, leaving the first at 920.
        drags.pointersChanged(920f)
        told.clear()
        drags.moved(900f)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a finger landing or lifting is no move`() {
        drags.down(1_000f)
        drags.moved(980f)
        told.clear()
        // The middle jumps up the screen as a second finger lands far above.
        drags.pointersChanged(600f)
        drags.moved(600f)
        drags.pointersChanged(980f)
        assertEquals(emptyList<Boolean>(), told)
    }
}
