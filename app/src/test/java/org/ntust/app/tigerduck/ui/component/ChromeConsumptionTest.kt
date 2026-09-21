package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [chromeConsumption] and [chromeFollow] only — the arithmetic of the split, not the
 * nested-scroll connection that calls them. [PullChromeConnectionTest] covers that: the source and finger-down
 * guards, how `used` folds into what onPreScroll returns, and the order the three consumers
 * unwind in. Only the release effect, which lives in the composable itself, is still untested.
 *
 * What is pinned here is the invariant the composition rests on: the chrome takes a signed share
 * that never exceeds what is left after the refresh pull has taken its own, so the caller can
 * report the sum as consumed. The pull's share arrives as `alreadyUsed`. And the bar hides only by
 * what the list itself scrolled, which is [chromeFollow]'s half.
 */
class ChromeConsumptionTest {

    private fun bar(height: Float = 200f) = AppBarState().apply { heightPx = height }

    private fun openDrawer(maxPx: Float = 56f) =
        SearchRevealState(maxPx).apply { consume(maxPx) }

    @Test
    fun `with no chrome nothing is consumed, in either direction`() {
        assertEquals(0f, chromeConsumption(-100f, 0f, null, null), 0.01f)
        assertEquals(0f, chromeConsumption(100f, 0f, null, null), 0.01f)
        assertEquals(0f, chromeConsumption(0f, 0f, null, null), 0.01f)
    }

    @Test
    fun `scrolling up shuts the drawer and leaves the bar to the list`() {
        val appBar = bar()
        val reveal = openDrawer()
        assertEquals(-56f, chromeConsumption(-100f, 0f, appBar, reveal), 0.01f)
        assertEquals(0f, reveal.revealPx, 0.01f)
        assertEquals(0f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `the chrome only ever takes what the pull left behind`() {
        val appBar = bar()
        val reveal = openDrawer()
        // The pull already claimed 80 of the 100 available.
        assertEquals(-20f, chromeConsumption(-100f, -80f, appBar, reveal), 0.01f)
        assertEquals(36f, reveal.revealPx, 0.01f)
        assertEquals(0f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `with the drawer shut an upward scroll goes wholly to the list`() {
        val appBar = bar()
        val reveal = SearchRevealState(initialMaxPx = 56f)
        assertEquals(0f, chromeConsumption(-100f, 0f, appBar, reveal), 0.01f)
        assertEquals(0f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `scrolling down brings the bar back and leaves the drawer alone`() {
        val appBar = bar()
        val reveal = SearchRevealState(initialMaxPx = 56f)
        chromeFollow(-500f, appBar)
        assertEquals(60f, chromeConsumption(60f, 0f, appBar, reveal), 0.01f)
        assertEquals(-140f, appBar.offsetPx, 0.01f)
        assertEquals(0f, reveal.revealPx, 0.01f)
    }

    @Test
    fun `a pinned drawer holds, and the chrome rides up with the list instead`() {
        val appBar = bar()
        val reveal = openDrawer().apply { pinned = true }
        assertEquals(0f, chromeConsumption(-30f, 0f, appBar, reveal), 0.01f)
        chromeFollow(-30f, appBar)
        assertEquals(56f, reveal.revealPx, 0.01f)
        assertEquals(-30f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `a bar of unmeasured height never eats a scroll`() {
        assertEquals(0f, chromeConsumption(-100f, 0f, AppBarState(), null), 0.01f)
        val unmeasured = AppBarState()
        chromeFollow(-100f, unmeasured)
        assertEquals(0f, unmeasured.offsetPx, 0.01f)
    }

    // --- chromeFollow: the bar goes only where the content went ----------------------------------

    @Test
    fun `the bar hides by exactly what the list scrolled`() {
        val appBar = bar()
        chromeFollow(-40f, appBar)
        assertEquals(-40f, appBar.offsetPx, 0.01f)
        chromeFollow(-500f, appBar)
        assertEquals(-200f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `a list that could not move leaves the bar where it is`() {
        // An empty page, or a list already at its end: the finger moved, the content did not.
        val appBar = bar()
        chromeFollow(0f, appBar)
        assertEquals(0f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `following never brings the bar back down`() {
        // That is chromeConsumption's job, before the list moves, so the bar returns wherever
        // the list happens to be rather than only when it can scroll back.
        val appBar = bar()
        chromeFollow(-100f, appBar)
        chromeFollow(30f, appBar)
        assertEquals(-100f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `with no bar there is nothing to follow`() {
        chromeFollow(-40f, null)
    }
}
