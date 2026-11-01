package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeSliderTickTest {

    @Test
    fun `the wait runs to the next whole second`() {
        assertEquals(1_000L, millisToNextSecond(5_000L))
        assertEquals(1L, millisToNextSecond(5_999L))
        assertEquals(250L, millisToNextSecond(5_750L))
    }

    @Test
    fun `a time before 1970 still waits less than a second`() {
        assertEquals(250L, millisToNextSecond(-1_250L))
    }
}
