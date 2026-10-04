package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AppFeature.CALENDAR
import org.ntust.app.tigerduck.data.model.AppFeature.CLASS_TABLE
import org.ntust.app.tigerduck.data.model.AppFeature.HOME
import org.ntust.app.tigerduck.data.model.AppFeature.LIBRARY

class ResetBottomBarPageTest {

    @Test
    fun `a default bar is not offered a reset`() {
        assertFalse(bottomBarDiffersFromDefault(AppFeature.defaultTabs, libraryEnabled = false))
        assertFalse(bottomBarDiffersFromDefault(AppFeature.defaultTabs, libraryEnabled = true))
    }

    @Test
    fun `a reordered bar is offered a reset`() {
        assertTrue(bottomBarDiffersFromDefault(listOf(CALENDAR, HOME, CLASS_TABLE), libraryEnabled = false))
    }

    @Test
    fun `a bar with fewer or more tabs is offered a reset`() {
        assertTrue(bottomBarDiffersFromDefault(listOf(HOME, CLASS_TABLE), libraryEnabled = false))
        assertTrue(bottomBarDiffersFromDefault(listOf(HOME, CLASS_TABLE, CALENDAR, LIBRARY), libraryEnabled = true))
    }

    @Test
    fun `a library tab the opt-in hides does not count as a change`() {
        val stored = listOf(HOME, CLASS_TABLE, CALENDAR, LIBRARY)
        assertFalse(bottomBarDiffersFromDefault(stored, libraryEnabled = false))
        assertEquals(AppFeature.defaultTabs, visibleBottomBarTabs(stored, libraryEnabled = false))
    }

    @Test
    fun `the demo loops only between two different bars, with animations on`() {
        val customized = listOf(CALENDAR, HOME)
        assertTrue(resetDemoLoops(customized, animate = true))
        assertFalse(resetDemoLoops(customized, animate = false))
        assertFalse(resetDemoLoops(AppFeature.defaultTabs, animate = true))
    }
}
