package org.ntust.app.tigerduck.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [slidesBetween] — which moves slide and which keep the tab
 * crossfade. Routes are written out literally; the bar here is Home,
 * Announcements and More.
 */
class NavTransitionsTest {

    private val tabs = setOf("home", "announcements", "more")

    @Test
    fun `opening a screen from a tab slides`() {
        assertTrue(slidesBetween(from = "announcements", to = "announcements/detail/{id}", isPop = false, tabRoutes = tabs))
    }

    @Test
    fun `opening a screen from a pushed screen slides`() {
        assertTrue(slidesBetween(from = "settings", to = "tabEditor", isPop = false, tabRoutes = tabs))
    }

    @Test
    fun `tapping a tab fades`() {
        assertFalse(slidesBetween(from = "home", to = "announcements", isPop = false, tabRoutes = tabs))
    }

    @Test
    fun `tapping a tab from inside a pushed screen fades`() {
        assertFalse(slidesBetween(from = "announcements/detail/{id}", to = "home", isPop = false, tabRoutes = tabs))
    }

    @Test
    fun `backing out of a pushed screen onto its tab slides`() {
        assertTrue(slidesBetween(from = "announcements/detail/{id}", to = "announcements", isPop = true, tabRoutes = tabs))
    }

    @Test
    fun `backing out of a pushed screen onto another pushed screen slides`() {
        assertTrue(slidesBetween(from = "tabEditor", to = "settings", isPop = true, tabRoutes = tabs))
    }

    @Test
    fun `backing from a tab to the first tab fades`() {
        assertFalse(slidesBetween(from = "announcements", to = "home", isPop = true, tabRoutes = tabs))
    }

    @Test
    fun `a feature opened from More without a tab slides`() {
        assertTrue(slidesBetween(from = "more", to = "score", isPop = false, tabRoutes = tabs))
    }
}
