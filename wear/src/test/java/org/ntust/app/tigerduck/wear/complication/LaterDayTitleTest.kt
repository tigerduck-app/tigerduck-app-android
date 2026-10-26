package org.ntust.app.tigerduck.wear.complication

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Covers [laterDayTitle], the long text complication's title for a class after today. */
@RunWith(RobolectricTestRunner::class)
class LaterDayTitleTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `tomorrow and a later weekday come before the time in Traditional Chinese`() {
        assertEquals("明天 08:10", laterDayTitle(context, daysAhead = 1, weekday = 2, startTime = "08:10"))
        assertEquals("週二 08:10", laterDayTitle(context, daysAhead = 2, weekday = 2, startTime = "08:10"))
    }

    @Test
    @Config(qualifiers = "en")
    fun `tomorrow and a later weekday come before the time in English`() {
        assertEquals("Tomorrow 08:10", laterDayTitle(context, daysAhead = 1, weekday = 2, startTime = "08:10"))
        assertEquals("Tue 08:10", laterDayTitle(context, daysAhead = 2, weekday = 2, startTime = "08:10"))
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `the same weekday a week ahead is named rather than called tomorrow`() {
        assertEquals("週一 08:10", laterDayTitle(context, daysAhead = 7, weekday = 1, startTime = "08:10"))
    }
}
