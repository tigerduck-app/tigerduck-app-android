package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one invariant the six screens sharing this component depend on: onPreScroll reports
 * exactly what it consumed. Too much and the list feels dead, too little and the scroll is
 * applied twice. The refresh pull's own share is passed in as `alreadyUsed`, so what is
 * asserted here is the *rest* of the split.
 */
class TigerPullToRefreshTest {

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
        val reveal = SearchRevealState(maxPx = 56f)
        chromeConsumption(-500f, 0f, appBar, reveal)
        assertEquals(0f, chromeConsumption(-100f, 0f, appBar, reveal), 0.01f)
    }

    @Test
    fun `scrolling down brings the bar back and leaves the drawer alone`() {
        val appBar = bar()
        val reveal = SearchRevealState(maxPx = 56f)
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
