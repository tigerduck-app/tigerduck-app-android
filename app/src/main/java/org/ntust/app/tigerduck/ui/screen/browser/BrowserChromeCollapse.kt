package org.ntust.app.tigerduck.ui.screen.browser

/**
 * Hides the browser's bars while the page scrolls down and brings them back on a scroll up, as
 * the iPhone's browser does (`BrowserChromeCollapse`). A scroll counts once it has gone
 * [jitterPx] one way, however small its steps; at the top of the page ([topZonePx]) the bars
 * show. The end of the page does not bring them back, on the iPhone either: only a scroll up does.
 *
 * A move counts only the way the student's finger last moved the page ([dragged]), a fling
 * included: the page also moves by itself, as when it lays out again while the bars move and the
 * WebView keeps its place, and such a move, either way, must not move the bars.
 *
 * Putting the bars away gives the page up to [growthPx] more room on screen, so a page laid out to
 * its own height can scroll that much less, while one laid out to the view's height scrolls as
 * far as before. A page left with no room past the top of the page once they are away is too
 * short for them to go: the top brings them back, and they stay for that page, as nothing would be
 * left to scroll up with to bring them back. Its room with the bars shown is then measured once
 * they and the keyboard have settled, and measured again after the keyboard moves; room past
 * that, or past what any page needs ([topZonePx] and [growthPx]), gives the bars another try:
 * content loaded late, or content laid out to the view's height, which keeps its room with the
 * bars away. [reset] and a zoom start afresh.
 *
 * Offsets the page did not scroll to are no scroll either: the first after a new page (where the
 * WebView may put back a page's old place), after a zoom (the page's laid-out width changed, which
 * the WebView tells before `onScaleChanged`) and after a resize ([resized]) only set where the next
 * scroll counts from. A resize in the [SETTLE_MS] after the bars move (by [now]) is theirs and
 * sets nothing back, so a scroll made meanwhile counts; what the page moves by itself then goes
 * against the drag and is left out. For [SETTLE_MS] after the keyboard comes or goes
 * ([keyboardMoved]) no offset is a scroll, whatever else moved: the WebView scrolls the field
 * being typed in into view as the page shrinks.
 */
internal class BrowserChromeCollapse(
    private val jitterPx: Int,
    private val topZonePx: Int,
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

    /** Whether the student's finger last moved the page down, toward its end; null before it has. */
    private var dragDown: Boolean? = null

    /** Whether the page proved too short for the bars to go: with them away it had no room past the top. */
    private var tooShort = false

    /** The too-short page's room with the bars shown and settled; null till measured. */
    private var shortRoom: Int? = null

    /**
     * [offset] is how far the page has scrolled from its top; [leftBelow] how far it can still go
     * down as laid out now; [contentWidth] how wide it is laid out, zoom and all.
     */
    fun scrolled(offset: Int, leftBelow: Int, contentWidth: Int) {
        if (contentWidth != lastWidth) {
            lastWidth = contentWidth
            forgetShort()
            rebase()
        }
        val last = lastOffset
        lastOffset = offset
        val room = offset + leftBelow
        if (tooShort && !collapsed && now() - movedAt >= SETTLE_MS && now() - keyboardAt >= SETTLE_MS) {
            val seen = shortRoom
            when {
                // Grown: the bars get another try.
                room > topZonePx + growthPx || (seen != null && room > seen + jitterPx) -> forgetShort()
                seen == null -> shortRoom = room
            }
        }
        if (offset <= topZonePx) {
            if (collapsed && room <= topZonePx) {
                tooShort = true
                shortRoom = null
            }
            collapsed = false
            travel = 0
            return
        }
        if (now() - keyboardAt < SETTLE_MS) {
            travel = 0
            return
        }
        val delta = offset - (last ?: return)
        if (delta == 0 || (delta > 0) != dragDown) return
        travel = if (travel == 0 || (delta > 0) == (travel > 0)) travel + delta else delta
        if (travel > jitterPx) {
            if (!tooShort) collapsed = true
        } else if (travel < -jitterPx) {
            collapsed = false
        }
    }

    /** The student's finger moved the page: [down] toward its end, or else toward its top. */
    fun dragged(down: Boolean) {
        dragDown = down
    }

    /** The touch was taken away: the page's moves are its own till the next drag. */
    fun dragCancelled() {
        dragDown = null
        travel = 0
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
        // A short page's room is measured again without it.
        shortRoom = null
        rebase()
    }

    private fun rebase() {
        lastOffset = null
        travel = 0
    }

    /** A new page, with the bars shown, and no drag yet. */
    fun reset() {
        collapsed = false
        dragDown = null
        forgetShort()
        rebase()
    }

    private fun forgetShort() {
        tooShort = false
        shortRoom = null
    }

    companion object {
        /** As the iPhone's, in dp. */
        const val JITTER_DP = 4
        const val TOP_ZONE_DP = 40

        /** The bars' heights, shown and collapsed, in dp. */
        const val TOP_BAR_DP = 56
        const val TOP_BAR_COLLAPSED_DP = 32
        const val BOTTOM_BAR_DP = 56

        /** How much taller the page gets with the bars collapsed, in dp: the top bar's loss, the bottom bar and its divider. */
        const val GROWTH_DP = TOP_BAR_DP - TOP_BAR_COLLAPSED_DP + BOTTOM_BAR_DP + 1

        /** How long the bars take to move, and the page with them, in ms. */
        const val SETTLE_MS = 700L
    }
}
