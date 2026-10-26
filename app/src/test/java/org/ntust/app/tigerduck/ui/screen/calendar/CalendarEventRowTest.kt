package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * The calendar's day list: a whole-day row shows no time, where "00:00" read as a midnight
 * deadline; a Moodle deadline at midnight still shows its time.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class CalendarEventRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun event(title: String, source: EventSource, dateTime: String) = CalendarEvent(
        eventId = title,
        title = title,
        date = Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.of("Asia/Taipei")).toInstant()),
        sourceRaw = source.raw,
    )

    private fun show(vararg events: CalendarEvent, onOpenAssignment: ((String) -> Unit)? = null) = composeRule.setContent {
        TigerDuckAppTheme(darkTheme = false) {
            Column { events.forEach { EventRow(event = it, onOpenAssignment = onOpenAssignment) } }
        }
    }

    @Test
    fun `a whole-day school row and a term boundary show no time`() {
        show(
            event("Midterm week", EventSource.SCHOOL, "2026-11-02T00:00"),
            event("First day of term", EventSource.SEMESTER, "2026-09-07T00:00"),
        )
        composeRule.onNodeWithText("Midterm week").assertExists()
        composeRule.onNodeWithText("First day of term").assertExists()
        composeRule.onNodeWithText("00:00").assertDoesNotExist()
    }

    @Test
    fun `a holiday with no reminder switch shows no time either`() {
        show(event("National Day", EventSource.HOLIDAY, "2026-10-10T00:00"))
        composeRule.onNodeWithText("00:00").assertDoesNotExist()
    }

    @Test
    fun `a Moodle deadline at midnight keeps its time`() {
        show(event("Lab report", EventSource.MOODLE, "2026-11-02T00:00"))
        composeRule.onNodeWithText("00:00").assertExists()
    }

    @Test
    fun `a Moodle deadline opens its assignment, and a school row opens nothing`() {
        val opened = mutableListOf<String>()
        show(
            event("Lab report", EventSource.MOODLE, "2026-11-02T23:59").copy(eventId = "moodle-4242"),
            event("Midterm week", EventSource.SCHOOL, "2026-11-02T00:00"),
            onOpenAssignment = { opened += it },
        )

        composeRule.onNodeWithText("Lab report").performClick()

        assertEquals(listOf("4242"), opened)
        composeRule.onNodeWithText("Midterm week").assertHasNoClickAction()
    }

    @Test
    fun `a timed school row keeps its time`() {
        show(event("Career fair", EventSource.SCHOOL, "2026-11-02T09:30"))
        composeRule.onNodeWithText("09:30").assertExists()
    }
}
