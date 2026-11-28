package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Test

// An update from an old version runs to some 20 steps, whose dots would run past both edges of the sheet.
class PageDotWindowTest {

    @Test
    fun `a few steps show every dot full size`() {
        assertEquals(0 until 9, PageDotWindow.visibleSteps(count = 9, current = 8))
        assertEquals(List(9) { 1f }, (0 until 9).map { PageDotWindow.scale(it, count = 9, current = 8) })
    }

    @Test
    fun `many steps show a window of nine that shrinks toward the hidden steps`() {
        assertEquals(0 until 9, PageDotWindow.visibleSteps(count = 40, current = 0))
        assertEquals(listOf(1f, 1f, 0.75f, 0.5f), listOf(0, 6, 7, 8).map { PageDotWindow.scale(it, count = 40, current = 0) })

        assertEquals(16 until 25, PageDotWindow.visibleSteps(count = 40, current = 20))
        assertEquals(
            listOf(0.5f, 0.75f, 1f, 0.75f, 0.5f),
            listOf(16, 17, 20, 23, 24).map { PageDotWindow.scale(it, count = 40, current = 20) },
        )

        assertEquals(31 until 40, PageDotWindow.visibleSteps(count = 40, current = 39))
        assertEquals(listOf(0.5f, 0.75f, 1f), listOf(31, 32, 39).map { PageDotWindow.scale(it, count = 40, current = 39) })
    }

    @Test
    fun `the current dot is never one that shrinks`() {
        for (current in 0 until 40) {
            assertEquals("step $current", 1f, PageDotWindow.scale(current, count = 40, current = current))
        }
    }
}
