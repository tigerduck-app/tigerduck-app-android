package org.ntust.app.tigerduck.ui.screen.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the browser's bars hide and come back, as iOS's `BrowserChromeCollapseTests`, and as Android's WebView scrolls. */
class BrowserChromeCollapseTest {

    private var clock = 0L
    private val chrome = BrowserChromeCollapse(jitterPx = 4, topZonePx = 40, growthPx = 81, now = { clock })

    /** Where [scrolled] last dragged the page to. */
    private var dragged = 0

    /** The student drags a long page, far from its end, 1080 px wide as laid out, to [offset]. */
    private fun scrolled(offset: Int, contentWidth: Int = 1_080) {
        if (offset != dragged) chrome.dragged(down = offset > dragged)
        dragged = offset
        chrome.scrolled(offset, leftBelow = 10_000, contentWidth = contentWidth)
    }

    /** The page moves to [offset] by itself, as the WebView tells it, with no finger on it. */
    private fun movedItself(offset: Int) = chrome.scrolled(offset, leftBelow = 10_000, contentWidth = 1_080)

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
    fun `the page moving up by itself while the student drags it down is no scroll`() {
        scrolled(100)
        scrolled(200)
        assertTrue(chrome.collapsed)
        // Laid out again as the bars go away, the page is moved up by the WebView.
        movedItself(182)
        assertTrue(chrome.collapsed)
        scrolled(260)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `the page moving down by itself while the student drags it up is no scroll`() {
        scrolled(100)
        scrolled(200)
        scrolled(150)
        assertFalse(chrome.collapsed)
        movedItself(284)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `before the student drags the page its own moves change nothing`() {
        movedItself(100)
        movedItself(300)
        assertFalse(chrome.collapsed)
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

    /**
     * A page [heightPx] tall, 1080 px wide, in a view 1000 px tall with the bars shown and 81 px
     * taller with them put away, scrolled as the WebView scrolls it: when the view grows at the
     * page's end, the WebView pulls the page up so it still fills the view, and tells that as a
     * scroll of its own.
     */
    private inner class Page(
        var heightPx: Int,
        private val chrome: BrowserChromeCollapse = this@BrowserChromeCollapseTest.chrome,
        /** For a page laid out to the view's height and this much more, which grows with the view. */
        var beyondViewPx: Int? = null,
        /** How the view grows, frame by frame, as the bars go: 81 px in all. */
        private val growthFrames: List<Int> = listOf(81),
    ) {
        var offset = 0
            private set

        /** How many times the bars have moved. */
        var flips = 0
            private set

        /** How much taller the view is now than with the bars shown, part way through their move. */
        private var grown = 0

        private val end get() = beyondViewPx ?: maxOf(0, heightPx - 1_000 - grown)

        /** The student drags the page [dy] down, or up if negative; it stops at either end. */
        fun drag(dy: Int) {
            chrome.dragged(down = dy > 0)
            val to = (offset + dy).coerceIn(0, end)
            if (to == offset) return
            offset = to
            tell()
        }

        private fun tell() {
            val was = chrome.collapsed
            chrome.scrolled(offset, leftBelow = end - offset, contentWidth = 1_080)
            if (chrome.collapsed == was) return
            flips++
            move()
        }

        /** The bars moved by themselves (a tap on the collapsed bar): the view follows. */
        fun barsMoved() = move()

        /** The bars move, a frame at a time, and the WebView pulls the page up as the view grows. */
        private fun move() {
            val toward = chrome.collapsed
            val steps = growthFrames.runningReduce(Int::plus)
            for (target in if (toward) steps else steps.dropLast(1).reversed() + 0) {
                if (chrome.collapsed != toward) return
                grown = target
                chrome.resized()
                if (offset > end) {
                    offset = end
                    tell()
                }
            }
        }
    }

    @Test
    fun `at the end of the page the bars stay away till a drag up`() {
        val page = Page(heightPx = 3_000)
        repeat(1_000) { page.drag(3) }
        assertTrue(chrome.collapsed)
        assertEquals("put away once, and not brought back by the end", 1, page.flips)
        page.drag(-10)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `bars put away at the end stay away, though putting them away pulls the page up`() {
        val page = Page(heightPx = 3_000)
        repeat(1_000) { page.drag(3) }
        // A tap on the collapsed bar brings them back at the end; the view shrinks, and the page
        // can scroll down again by the bars' room.
        chrome.expand()
        page.barsMoved()
        val flips = page.flips
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
        assertEquals("put away once more, and left away", flips + 1, page.flips)
    }

    @Test
    fun `a page too short to scroll past the top with the bars away keeps them after one try`() {
        // 80, 100 and 121 px to scroll with the bars shown: none, 19 and 40 with them away.
        for (height in listOf(1_080, 1_100, 1_121)) {
            val chrome = BrowserChromeCollapse(jitterPx = 4, topZonePx = 40, growthPx = 81, now = { clock })
            val page = Page(heightPx = height, chrome = chrome)
            repeat(100) { page.drag(3) }
            // Away at most once, which leaves the page nowhere to scroll up from, and back for good.
            assertFalse("$height px tall", chrome.collapsed)
            val flips = page.flips
            assertTrue("$height px tall: the bars moved $flips times", flips <= 2)
            repeat(10) {
                repeat(30) { page.drag(-3) }
                repeat(30) { page.drag(3) }
            }
            assertEquals("$height px tall, dragged on", flips, page.flips)
        }
        // 41 px to scroll with them away: past the top.
        val page = Page(heightPx = 1_122)
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a page laid out to the view's height keeps its room with the bars away, and they go`() {
        // 100 px to scroll past the view's height, with the bars shown or away.
        val page = Page(heightPx = 0, beyondViewPx = 100)
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
        assertEquals(1, page.flips)
    }

    @Test
    fun `a short page that grows lets the bars go away again`() {
        val page = Page(heightPx = 1_080)
        repeat(100) { page.drag(3) }
        assertFalse(chrome.collapsed)
        // Its content loaded late, once the bars had settled.
        clock += 10_000
        page.heightPx = 3_000
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a short page whose content comes laid out to the view's height lets the bars go again`() {
        val page = Page(heightPx = 1_080)
        repeat(100) { page.drag(3) }
        assertFalse(chrome.collapsed)
        // A placeholder gave way to content as tall as the view and 100 px more, which keeps its
        // room with the bars away; the page's width stayed the same.
        clock += 10_000
        repeat(30) { page.drag(-3) }
        repeat(30) { page.drag(3) }
        page.beyondViewPx = 100
        repeat(30) { page.drag(-3) }
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a short page found part way through the bars' move lets them go once its content grows`() {
        // 80 px to scroll with the bars shown; the WebView pulls the page up 40 px into their move.
        val page = Page(heightPx = 1_080, growthFrames = listOf(40, 41))
        repeat(100) { page.drag(3) }
        assertFalse(chrome.collapsed)
        clock += 10_000
        repeat(30) { page.drag(-3) }
        repeat(30) { page.drag(3) }
        // Content as tall as the view and 100 px more takes the placeholder's place.
        page.beyondViewPx = 100
        repeat(30) { page.drag(-3) }
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a keyboard that came and went leaves the short page a try once its content grows`() {
        val page = Page(heightPx = 1_080)
        repeat(100) { page.drag(3) }
        assertFalse(chrome.collapsed)
        // The keyboard comes, and while it is up the page can scroll 380 px; then it goes.
        chrome.keyboardMoved()
        clock += 10_000
        chrome.scrolled(0, leftBelow = 380, contentWidth = 1_080)
        chrome.keyboardMoved()
        clock += 10_000
        // Short again: the bars may try once more, and come back.
        repeat(30) { page.drag(-3) }
        repeat(100) { page.drag(3) }
        clock += 10_000
        repeat(30) { page.drag(-3) }
        repeat(30) { page.drag(3) }
        assertFalse(chrome.collapsed)
        // Content of 100 px more than the view takes the placeholder's place: room past the 80 measured.
        page.beyondViewPx = 100
        repeat(30) { page.drag(-3) }
        repeat(100) { page.drag(3) }
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a new page waits for a drag before its own moves count`() {
        scrolled(100)
        scrolled(200)
        chrome.reset()
        movedItself(500)
        movedItself(600)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a touch taken away leaves the page's moves to itself`() {
        scrolled(100)
        chrome.dragCancelled()
        movedItself(300)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a short page's room while the bars come back is no growth`() {
        // 100 px to scroll with the bars shown, 19 with them away.
        val page = Page(heightPx = 1_100)
        repeat(100) { page.drag(3) }
        val flips = page.flips
        clock += 10_000
        repeat(10) {
            repeat(30) { page.drag(-3) }
            repeat(30) { page.drag(3) }
        }
        assertFalse(chrome.collapsed)
        assertEquals(flips, page.flips)
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
    fun `the bars' growth is the top bar's loss, the bottom bar and its divider`() {
        assertEquals(81, BrowserChromeCollapse.GROWTH_DP)
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
}
