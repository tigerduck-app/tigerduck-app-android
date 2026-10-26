package org.ntust.app.tigerduck.ui.screen.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the browser's bars hide and come back, as iOS's `BrowserChromeCollapseTests`, and as Android's WebView scrolls. */
class BrowserChromeCollapseTest {

    private var clock = 0L
    private val chrome = BrowserChromeCollapse(jitterPx = 4, topZonePx = 40, endZonePx = 96, growthPx = 81, now = { clock })

    /** A long page, far from its end, 1080 px wide as laid out. */
    private fun scrolled(offset: Int, contentWidth: Int = 1_080) = chrome.scrolled(offset, leftBelow = 10_000, contentWidth = contentWidth)

    @Test
    fun `scrolling down hides the bars and up brings them back`() {
        scrolled(50)
        scrolled(100)
        assertTrue(chrome.collapsed)
        scrolled(300)
        assertTrue(chrome.collapsed)
        scrolled(290)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `jitter and the top of the page change nothing`() {
        scrolled(0)
        scrolled(30)
        assertFalse(chrome.collapsed)
        scrolled(200)
        scrolled(202)
        assertTrue(chrome.collapsed)
        scrolled(199)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `small steps add up, down and up`() {
        scrolled(100)
        for (offset in 101..103) scrolled(offset)
        assertFalse("three pixels down, within the jitter", chrome.collapsed)
        for (offset in 104..160) scrolled(offset)
        assertTrue(chrome.collapsed)
        for (offset in 159 downTo 150) scrolled(offset)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a turn starts the count again`() {
        scrolled(100)
        scrolled(103)
        scrolled(101)
        scrolled(104)
        assertFalse("three down, two up, three down: never past the jitter one way", chrome.collapsed)
    }

    @Test
    fun `back at the top of the page the bars show`() {
        scrolled(300)
        scrolled(400)
        assertTrue(chrome.collapsed)
        // A jump to the top, as a scroll the page does itself.
        scrolled(0)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `near the end of the page the bars show`() {
        chrome.scrolled(300, leftBelow = 1_000, contentWidth = 1_080)
        chrome.scrolled(400, leftBelow = 900, contentWidth = 1_080)
        assertTrue(chrome.collapsed)
        chrome.scrolled(1_250, leftBelow = 50 - 81, contentWidth = 1_080)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `scrolling down to the end puts the bars away once and brings them back once, however the page resizes`() {
        // A page 3000 px tall in a view 1000 px tall with the bars shown, scrolled down 3 px at a time;
        // collapsed, the view is 81 px taller.
        val range = 3_000
        var offset = 0
        var flips = 0
        var was = chrome.collapsed
        while (true) {
            val extent = 1_000 + if (chrome.collapsed) 81 else 0
            offset = minOf(offset + 3, range - extent)
            chrome.scrolled(offset, leftBelow = range - extent - offset, contentWidth = 1_080)
            if (chrome.collapsed != was) flips++
            was = chrome.collapsed
            if (range - extent - offset == 0 && !chrome.collapsed) break
            if (flips > 10) break
        }
        assertEquals("down: collapsed once, then shown once at the end", 2, flips)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a page that comes back to its old place is no scroll`() {
        scrolled(100)
        scrolled(200)
        chrome.reset()
        assertFalse(chrome.collapsed)
        // The WebView puts a page met before back where it was.
        scrolled(500)
        assertFalse(chrome.collapsed)
        scrolled(510)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `an offset a resize moved is no scroll`() {
        clock = 10_000
        scrolled(300)
        chrome.resized()
        scrolled(900)
        assertFalse(chrome.collapsed)
        scrolled(910)
        assertTrue(chrome.collapsed)
        clock += 10_000
        chrome.resized()
        scrolled(400)
        assertTrue("a resize keeps the bars as they are", chrome.collapsed)
    }

    @Test
    fun `a pinch's offset is no scroll, though the WebView tells it before the zoom`() {
        scrolled(300)
        // Zoomed in: the page is twice as wide, and its offset doubles with it.
        scrolled(600, contentWidth = 2_160)
        assertFalse(chrome.collapsed)
        scrolled(610, contentWidth = 2_160)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `the bars' own resize leaves a scroll made meanwhile to count`() {
        clock = 10_000
        scrolled(100)
        scrolled(200)
        assertTrue(chrome.collapsed)
        // The bars shrink over some frames, the page growing each frame, while the student scrolls back up.
        for (offset in listOf(190, 180, 170)) {
            clock += 16
            chrome.resized()
            scrolled(offset)
        }
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `the keyboard coming up just after the bars came back is no scroll, its page's move neither`() {
        clock = 10_000
        scrolled(100)
        scrolled(200)
        chrome.expand()
        // A field tapped at once: the keyboard comes, the page shrinks, and the WebView scrolls the field into view.
        clock += 100
        chrome.keyboardMoved()
        clock += 16
        chrome.resized()
        scrolled(500)
        clock += 300
        scrolled(560)
        assertFalse(chrome.collapsed)
        // Once it has settled, a scroll is a scroll again.
        clock += BrowserChromeCollapse.SETTLE_MS
        scrolled(570)
        scrolled(580)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `tapping the collapsed bar brings the bars back, till the page scrolls down again`() {
        scrolled(100)
        scrolled(200)
        chrome.expand()
        assertFalse(chrome.collapsed)
        scrolled(260)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `the bars' growth is the top bar's loss, the bottom bar and its divider`() {
        assertEquals(81, BrowserChromeCollapse.GROWTH_DP)
    }
}
