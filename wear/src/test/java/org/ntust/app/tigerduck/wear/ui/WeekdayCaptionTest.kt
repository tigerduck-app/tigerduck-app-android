package org.ntust.app.tigerduck.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.wear.R

/** Covers [weekdayCaptionRes], the day Now & Next, the tile and the complication give a later class. */
class WeekdayCaptionTest {

    @Test
    fun `each weekday from Monday takes its own caption rather than its short name`() {
        assertEquals(
            listOf(
                R.string.weekday_mon_caption,
                R.string.weekday_tue_caption,
                R.string.weekday_wed_caption,
                R.string.weekday_thu_caption,
                R.string.weekday_fri_caption,
                R.string.weekday_sat_caption,
                R.string.weekday_sun_caption,
            ),
            (1..7).map(::weekdayCaptionRes),
        )
    }
}
