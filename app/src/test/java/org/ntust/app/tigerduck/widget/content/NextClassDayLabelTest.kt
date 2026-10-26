package org.ntust.app.tigerduck.widget.content

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers [futureDayLabel] and [futureCourseTimeLabel], the Next class widget's
 * day for a class after today, in the strings the app ships.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class NextClassDayLabelTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `a later weekday is named by its caption in Traditional Chinese`() {
        // Friday, with Monday's first class next.
        assertEquals("週一", futureDayLabel(context, todayWeekday = 5, futureWeekday = 1))
        assertEquals(
            "週二 08:10",
            futureCourseTimeLabel(context, todayWeekday = 7, futureWeekday = 2, startTime = "08:10"),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun `a later weekday is named by its caption in English`() {
        assertEquals("Mon", futureDayLabel(context, todayWeekday = 5, futureWeekday = 1))
        assertEquals(
            "Tue 08:10",
            futureCourseTimeLabel(context, todayWeekday = 7, futureWeekday = 2, startTime = "08:10"),
        )
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `the next calendar day is still tomorrow`() {
        assertEquals("明天", futureDayLabel(context, todayWeekday = 1, futureWeekday = 2))
        // Sunday wraps to Monday.
        assertEquals("明天", futureDayLabel(context, todayWeekday = 7, futureWeekday = 1))
        assertEquals(
            "明天 08:10",
            futureCourseTimeLabel(context, todayWeekday = 1, futureWeekday = 2, startTime = "08:10"),
        )
        assertEquals(
            "明天 08:10",
            futureCourseTimeLabel(context, todayWeekday = 1, futureWeekday = null, startTime = "08:10"),
        )
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `the same weekday a week ahead is named rather than called tomorrow`() {
        assertEquals("週一", futureDayLabel(context, todayWeekday = 1, futureWeekday = 1))
    }
}
