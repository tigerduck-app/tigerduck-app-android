package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.ui.component.STACKED_ROW_FONT_SCALE
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/**
 * The calendar's row at the largest font sizes: its time or holiday switch goes under the title,
 * at its start, the title keeps the row's width, and the source's bar spans the row, as Home's
 * assignment row stacks and as the iPhone's calendar row does at its accessibility text sizes.
 * Below them the row is as it was.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, and a window wider than the row.
@Config(application = Application::class, qualifiers = "w480dp-h800dp")
// Real drawing and text, so the bar's pixels and the lines' widths are real.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarEventRowLargeTextTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val deadline = CalendarEvent(
        eventId = CalendarEvent.moodleEventId("1"),
        title = "Homework 1: read chapters three to five and write a summary of each",
        date = Date.from(LocalDateTime.parse("2026-11-02T23:59").atZone(ZoneId.of("Asia/Taipei")).toInstant()),
        sourceRaw = EventSource.MOODLE.raw,
    )

    private val holiday = deadline.copy(eventId = "holiday-1", title = "National Day", sourceRaw = EventSource.HOLIDAY.raw)

    /** [event]'s row in a 360 wide box, at density 1 so a dp is a pixel, and at [fontScale]. */
    private fun show(event: CalendarEvent, fontScale: Float, notifyOnHoliday: Boolean? = null) {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.width(ROW_WIDTH.dp).background(MaterialTheme.colorScheme.surface).testTag(ROW)) {
                        EventRow(event, notifyOnHoliday = notifyOnHoliday, onNotifyOnHolidayChange = notifyOnHoliday?.let { { _: Boolean -> } })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(text: String): Rect =
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val moodle get() = composeRule.activity.getString(R.string.calendar_source_moodle)

    // From 1.5 on, the first of them: the threshold itself stacks.
    @Test
    fun `at the largest font sizes the time goes under the title, at its start`() {
        show(deadline, fontScale = STACKED_ROW_FONT_SCALE)

        val title = bounds(deadline.title)
        val time = bounds("23:59")
        assertTrue("the time is beside the title: $time, title $title", time.top >= bounds(moodle).bottom)
        assertEquals("the time is not at the title's start", title.left, time.left, 0.5f)
    }

    // Beside the time the title wrapped into a strip a few letters wide.
    @Test
    fun `at the largest font sizes the title keeps the row's width`() {
        show(deadline, fontScale = 2f)

        // The row less its padding, the bar and the gap after it.
        assertEquals(ROW_WIDTH - 16f * 2 - 4f - 12f, bounds(deadline.title).width, 0.5f)
    }

    @Test
    fun `at the largest font sizes a holiday's switch goes under the title`() {
        show(holiday, fontScale = 2f, notifyOnHoliday = false)

        val title = bounds(holiday.title)
        val switch = composeRule.onNode(isToggleable(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the switch is beside the title: $switch, title $title", switch.top >= title.bottom)
    }

    @Test
    fun `at the largest font sizes the source's bar spans the row`() {
        show(deadline, fontScale = 2f)

        val row = composeRule.onNodeWithTag(ROW).captureToImage().toPixelMap()
        val color = EventSource.MOODLE.color
        // The bar is 4 wide inside 16 of padding, and the row has 6 above and below it.
        assertNear("the bar's top", color, row[16 + 2, 6 + 2])
        assertNear("the bar's bottom", color, row[16 + 2, row.height - 6 - 3])
    }

    @Test
    fun `below the largest font sizes the time stays beside the title`() {
        show(deadline, fontScale = 1.3f)

        val title = bounds(deadline.title)
        val time = bounds("23:59")
        assertTrue("the time is not beside the title: $time, title $title", time.left >= title.right)
    }

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    private companion object {
        const val ROW_WIDTH = 360
        const val ROW = "row"
    }
}
