package org.ntust.app.tigerduck.ui.screen.browser

/**
 * Hides the browser's bars while the page scrolls down and brings them back on a scroll up, as
 * the iPhone's browser does (`BrowserChromeCollapse`). A scroll counts once it has gone
 * [jitterPx] one way, however small its steps; at the top of the page ([topZonePx]) and near its
 * end ([endZonePx]) the bars show.
 *
 * Collapsing makes the page [growthPx] taller, so it can scroll that much less: the end is
 * measured as with the bars shown, or the room the collapse gave would bring them back, and the
 * next scroll would put them away again. Offsets the page did not scroll to are no scroll: the
 * first after a new page (where the WebView may put back a page's old place), after a zoom (the
 * page's laid-out width changed, which the WebView tells before `onScaleChanged`) and after a
 * resize ([resized]) only set where the next scroll counts from. The bars' own resize, for
 * [SETTLE_MS] after they move by [now], moves no offset, so a scroll made meanwhile counts. For
 * [SETTLE_MS] after the keyboard comes or goes ([keyboardMoved]) no offset is a scroll, whatever
 * else moved: the WebView scrolls the field being typed in into view as the page shrinks.
 */
internal class BrowserChromeCollapse(
    private val jitterPx: Int,
    private val topZonePx: Int,
    private val endZonePx: Int,
    private val growthPx: Int,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var collapsed = false
        private set(value) {
            if (field != value) movedAt = now()
            field = value
        }

    /** When the bars last moved, by [now]. */
    private var movedAt = Long.MIN_VALUE / 2

    /** When the keyboard last came or went, by [now]. */
    private var keyboardAt = Long.MIN_VALUE / 2

    /** The page's laid-out width at the last scroll, which a zoom changes. */
    private var lastWidth: Int? = null

    /** The offset the next scroll counts from; null till one is known. */
    private var lastOffset: Int? = null

    /** How far the page has gone one way since it last turned, down positive. */
    private var travel = 0

    /**
     * [offset] is how far the page has scrolled from its top; [leftBelow] how far it can still go
     * down as laid out now; [contentWidth] how wide it is laid out, zoom and all.
     */
    fun scrolled(offset: Int, leftBelow: Int, contentWidth: Int) {
        if (contentWidth != lastWidth) {
            lastWidth = contentWidth
            rebase()
        }
        val last = lastOffset
        lastOffset = offset
        val atEnd = leftBelow + (if (collapsed) growthPx else 0) <= endZonePx
        if (offset <= topZonePx || atEnd) {
            collapsed = false
            travel = 0
            return
        }
        if (now() - keyboardAt < SETTLE_MS) {
            travel = 0
            return
        }
        val delta = offset - (last ?: return)
        if (delta == 0) return
        travel = if (travel == 0 || (delta > 0) == (travel > 0)) travel + delta else delta
        if (travel > jitterPx) collapsed = true else if (travel < -jitterPx) collapsed = false
    }

    /** The bars back, until the page scrolls down again. */
    fun expand() {
        collapsed = false
        travel = 0
    }

    /** The page was resized: by the keyboard, say, which may move its offset; not by the bars' own move. */
    fun resized() {
        if (now() - keyboardAt < SETTLE_MS || now() - movedAt >= SETTLE_MS) rebase()
    }

    /** The keyboard came up or went down. */
    fun keyboardMoved() {
        keyboardAt = now()
        rebase()
    }

    private fun rebase() {
        lastOffset = null
        travel = 0
    }

    /** A new page, with the bars shown. */
    fun reset() {
        collapsed = false
        rebase()
    }

    companion object {
        /** As the iPhone's, in dp. */
        const val JITTER_DP = 4
        const val TOP_ZONE_DP = 40

        /** How near the end of the page counts as its end, in dp. */
        const val END_ZONE_DP = 96

        /** The bars' heights, shown and collapsed, in dp. */
        const val TOP_BAR_DP = 56
        const val TOP_BAR_COLLAPSED_DP = 32
        const val BOTTOM_BAR_DP = 56

        /** How long the bars take to move, and the page with them, in ms. */
        const val SETTLE_MS = 700L

        /** How much taller the page gets with the bars collapsed, in dp: the top bar's loss, the bottom bar and its divider. */
        const val GROWTH_DP = TOP_BAR_DP - TOP_BAR_COLLAPSED_DP + BOTTOM_BAR_DP + 1
    }
}
