package org.ntust.app.tigerduck.wear.ui

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.wear.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** How Now & Next and the complication give a class's times, as the iPhone's watch does. */
@RunWith(RobolectricTestRunner::class)
class ClassTimesTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a class's times read as its full range`() {
        assertEquals("08:10–09:00", rangeText(8 * 60 + 10, 9 * 60))
        assertEquals("10:20–12:10", rangeText(10 * 60 + 20, 12 * 60 + 10))
    }

    @Test
    @Config(qualifiers = "en")
    fun `a screen reader hears the range in words in English`() {
        assertEquals("10:20 to 12:10", spokenRange(context, 10 * 60 + 20, 12 * 60 + 10))
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `a screen reader hears the range in words in Traditional Chinese`() {
        assertEquals("10:20 至 12:10", spokenRange(context, 10 * 60 + 20, 12 * 60 + 10))
    }

    @Test
    fun `a class next today is headed Next, never Today, then Tomorrow, then its weekday`() {
        assertEquals(R.string.watch_next, nextHeadlineRes(daysAhead = 0, weekday = 1))
        assertEquals(R.string.widget_tomorrow, nextHeadlineRes(daysAhead = 1, weekday = 2))
        assertEquals(R.string.weekday_thu_caption, nextHeadlineRes(daysAhead = 3, weekday = 4))
        assertEquals(R.string.weekday_mon_caption, nextHeadlineRes(daysAhead = 7, weekday = 1))
    }

    @Test
    @Config(qualifiers = "en")
    fun `a card's caption is its headline, then its range`() {
        assertEquals("NOW · 10:20–12:10", cardCaption(context.getString(R.string.watch_now), 10 * 60 + 20, 12 * 60 + 10))
        assertEquals(
            "Tomorrow · 08:10–09:00",
            cardCaption(context.getString(nextHeadlineRes(1, 2)), 8 * 60 + 10, 9 * 60),
        )
        assertEquals("Tue · 08:10–09:00", cardCaption(context.getString(nextHeadlineRes(2, 2)), 8 * 60 + 10, 9 * 60))
    }
}
