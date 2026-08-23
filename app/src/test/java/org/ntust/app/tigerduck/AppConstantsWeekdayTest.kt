package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * Calendar counts weekdays from Sunday; course schedules count from Monday.
 * Home and the class table both key off this, so the off-by-one is worth
 * pinning in one place rather than in each caller.
 */
class AppConstantsWeekdayTest {

    @Test
    fun `calendar weekdays map to monday-first schedule keys`() {
        assertEquals(1, AppConstants.weekdayIndex(Calendar.MONDAY))
        assertEquals(5, AppConstants.weekdayIndex(Calendar.FRIDAY))
        // Sunday is 1 in Calendar and 7 here — the off-by-one that makes this
        // worth a test at all, and the reason both screens share one copy.
        assertEquals(7, AppConstants.weekdayIndex(Calendar.SUNDAY))
    }

    @Test
    fun `an out-of-range value falls back to monday rather than throwing`() {
        assertEquals(1, AppConstants.weekdayIndex(0))
        assertEquals(1, AppConstants.weekdayIndex(99))
    }
}
