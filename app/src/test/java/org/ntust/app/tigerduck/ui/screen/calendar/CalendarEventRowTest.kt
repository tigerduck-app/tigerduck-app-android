package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
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
 * deadline; a Moodle deadline at midnight still shows its time. A deadline that opens its
 * assignment is a button with a chevron, which TalkBack does not read; other rows open nothing.
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

    /** Each of [events] in a row, which opens through [onOpen] where it gives one. */
    private fun show(vararg events: CalendarEvent, onOpen: (CalendarEvent) -> (() -> Unit)? = { null }) = composeRule.setContent {
        TigerDuckAppTheme(darkTheme = false) {
            Column { events.forEach { EventRow(event = it, onOpen = onOpen(it)) } }
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
    fun `a deadline that opens is a button, and a school row opens nothing`() {
        val opened = mutableListOf<String>()
        show(
            event("Lab report", EventSource.MOODLE, "2026-11-02T23:59"),
            event("Midterm week", EventSource.SCHOOL, "2026-11-02T00:00"),
            onOpen = { row -> if (row.source == EventSource.MOODLE) { { opened += row.title } } else null },
        )

        composeRule.onNodeWithText("Lab report").performClick()

        assertEquals(listOf("Lab report"), opened)
        composeRule.onNodeWithText("Midterm week").assertHasNoClickAction()
    }

    @Test
    fun `only a row that opens shows a chevron`() {
        show(
            event("Lab report", EventSource.MOODLE, "2026-11-02T23:59"),
            event("Quiz of a hidden course", EventSource.MOODLE, "2026-11-02T23:59"),
            onOpen = { row -> if (row.title == "Lab report") { {} } else null },
        )

        composeRule.onAllNodesWithTag(EVENT_ROW_CHEVRON_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithText("Quiz of a hidden course").assertHasNoClickAction()
    }

    @Test
    fun `TalkBack reads a row that opens by its text alone, not its chevron`() {
        show(event("Lab report", EventSource.MOODLE, "2026-11-02T23:59"), onOpen = { {} })

        val row = composeRule.onNode(hasClickAction()).fetchSemanticsNode().config
        assertFalse(
            "the chevron is read: ${row.getOrNull(SemanticsProperties.ContentDescription)}",
            row.contains(SemanticsProperties.ContentDescription),
        )
        assertEquals(
            listOf("Lab report", composeRule.activity.getString(R.string.calendar_source_moodle), "23:59"),
            row[SemanticsProperties.Text].map { it.text },
        )
    }

    @Test
    fun `a timed school row keeps its time`() {
        show(event("Career fair", EventSource.SCHOOL, "2026-11-02T09:30"))
        composeRule.onNodeWithText("09:30").assertExists()
    }
}
