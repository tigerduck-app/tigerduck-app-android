package org.ntust.app.tigerduck.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which taps on the Class Table tab make a double tap that switches class tables. */
class ClassTableTabTapsTest {

    private val taps = ClassTableTabTaps(doubleTapTimeoutMs = 300)

    private fun tap(downMs: Long, upMs: Long, showing: Boolean = true): Boolean {
        taps.down(downMs, showing)
        return taps.tapped(upMs)
    }

    @Test
    fun `two quick taps on the shown class table make a double tap`() {
        assertFalse(tap(0, 80))
        assertTrue(tap(300, 380))
    }

    @Test
    fun `a second tap after the timeout is a new first tap`() {
        assertFalse(tap(0, 80))
        assertFalse(tap(381, 460))
        assertTrue(tap(600, 680))
    }

    @Test
    fun `a double tap begun on another tab does not count, as its first tap opened the class table`() {
        assertFalse(tap(0, 80, showing = false))
        assertFalse(tap(200, 280, showing = true))
        // The next pair starts over, now on the class table.
        assertFalse(tap(1_000, 1_080))
        assertTrue(tap(1_200, 1_280))
    }

    @Test
    fun `three quick taps make one double tap`() {
        assertFalse(tap(0, 50))
        assertTrue(tap(150, 200))
        assertFalse(tap(300, 350))
    }

    @Test
    fun `a long press or a touch that slides away leaves nothing to pair with`() {
        assertFalse(tap(0, 50))
        taps.down(100, true)
        taps.interrupted()
        assertFalse(tap(200, 250))
    }
}
