package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [chromeConsumption] only — the arithmetic of the split, not the nested-scroll
 * connection that calls it. [PullChromeConnectionTest] covers that: the source and finger-down
 * guards, how `used` folds into what onPreScroll returns, and the order the three consumers
 * unwind in. Only the release effect, which lives in the composable itself, is still untested.
 *
 * What is pinned here is the invariant the composition rests on: the chrome takes a signed share
 * that never exceeds what is left after the refresh pull has taken its own, so the caller can
 * report the sum as consumed. The pull's share arrives as `alreadyUsed`.
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
    fun `scrolling up drains the drawer before it hides the bar`() {
        val appBar = bar()
        val reveal = openDrawer()
        assertEquals(-100f, chromeConsumption(-100f, 0f, appBar, reveal), 0.01f)
        assertEquals(0f, reveal.revealPx, 0.01f)
        assertEquals(-44f, appBar.offsetPx, 0.01f)
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
    fun `nothing is consumed once the bar is hidden and the drawer is shut`() {
        val appBar = bar()
        val reveal = SearchRevealState(initialMaxPx = 56f)
        chromeConsumption(-500f, 0f, appBar, reveal)
        assertEquals(0f, chromeConsumption(-100f, 0f, appBar, reveal), 0.01f)
    }

    @Test
    fun `scrolling down brings the bar back and leaves the drawer alone`() {
        val appBar = bar()
        val reveal = SearchRevealState(initialMaxPx = 56f)
        chromeConsumption(-500f, 0f, appBar, reveal)
        assertEquals(60f, chromeConsumption(60f, 0f, appBar, reveal), 0.01f)
        assertEquals(-140f, appBar.offsetPx, 0.01f)
        assertEquals(0f, reveal.revealPx, 0.01f)
    }

    @Test
    fun `a pinned drawer hands its share to the bar`() {
        val appBar = bar()
        val reveal = openDrawer().apply { pinned = true }
        assertEquals(-30f, chromeConsumption(-30f, 0f, appBar, reveal), 0.01f)
        assertEquals(56f, reveal.revealPx, 0.01f)
        assertEquals(-30f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `a bar of unmeasured height never eats a scroll`() {
        assertEquals(0f, chromeConsumption(-100f, 0f, AppBarState(), null), 0.01f)
    }
}
