package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * What TalkBack reads in the calendar: each day cell is one stop naming the full date and how
 * many events it holds, as the iPhone's `a11y_calendar_day` label does, and only the selected
 * day says it is selected ("not selected" on every other day would be noise); the date over the
 * day's events is a heading to jump to.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class CalendarAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val taipei = ZoneId.of("Asia/Taipei")
    private val hasNoSelectedState = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)
    private fun LocalDate.toDate(): Date = Date.from(atStartOfDay(taipei).toInstant())

    private val day = LocalDate.of(2026, 10, 14)
    private val events = listOf(
        CalendarEvent("a", "Lab report", day.atTime(23, 59).atZone(taipei).toInstant().let(Date::from), EventSource.MOODLE.raw),
        CalendarEvent("b", "Career fair", day.atTime(9, 30).atZone(taipei).toInstant().let(Date::from), EventSource.SCHOOL.raw),
    )

    private fun showGrid(selected: LocalDate) = composeRule.setContent {
        TigerDuckAppTheme(darkTheme = false) {
            CalendarGrid(
                monthDate = LocalDate.of(2026, 10, 1).toDate(),
                selectedDate = selected.toDate(),
                eventsByDay = mapOf(day to events),
                onDateSelected = {},
            )
        }
    }

    @Test
    fun `a day cell names its full date and its number of events`() {
        showGrid(selected = day.plusDays(1))
        composeRule.onNodeWithContentDescription("Wednesday, October 14, 2026, 2 events")
            .assertHasClickAction()
            .assert(hasNoSelectedState)
        composeRule.onNodeWithContentDescription("Thursday, October 15, 2026, 0 events").assertIsSelected()
    }

    @Test
    fun `the selected day says so`() {
        showGrid(selected = day)
        composeRule.onNodeWithContentDescription("Wednesday, October 14, 2026, 2 events").assertIsSelected()
        composeRule.onNodeWithContentDescription("Thursday, October 15, 2026, 0 events").assert(hasNoSelectedState)
    }

    @Test
    fun `a cell is read once, not as its number and dots again`() {
        showGrid(selected = day)
        composeRule.onAllNodesWithText("14").assertCountEquals(0)
    }

    @Test
    fun `the date over the day's events is a heading`() {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) { CalendarDayHeading(date = day, today = day) }
        }
        composeRule.onNodeWithText("Oct 14 (Today)").assert(isHeading())
    }
}
