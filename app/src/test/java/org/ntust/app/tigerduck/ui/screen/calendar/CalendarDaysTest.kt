package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// Robolectric for the locale's own month-day pattern, which comes from ICU.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CalendarDaysTest {

    private var id = 0

    private fun event(source: EventSource, dateTime: String, zone: String = "Asia/Taipei") = CalendarEvent(
        eventId = "e${id++}",
        title = "t",
        date = Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.of(zone)).toInstant()),
        sourceRaw = source.raw,
    )

    // --- grouping: read once, by the Taipei date --------------------------------------------

    @Test
    fun `events are filed under their Taipei date, whatever zone they were made in`() {
        val lateUtc = event(EventSource.MOODLE, "2026-10-13T23:30", zone = "UTC") // 07:30 on the 14th in Taipei
        val morning = event(EventSource.SCHOOL, "2026-10-14T09:00")
        val nextDay = event(EventSource.HOLIDAY, "2026-10-15T00:00")

        val days = groupByTaipeiDay(listOf(lateUtc, morning, nextDay))

        assertEquals(listOf(lateUtc, morning), days[LocalDate.of(2026, 10, 14)])
        assertEquals(listOf(nextDay), days[LocalDate.of(2026, 10, 15)])
        assertEquals(null, days[LocalDate.of(2026, 10, 13)])
    }

    // --- dots: one per source, in the iPhone's order -----------------------------------------

    @Test
    fun `a day's dots are one per source, Moodle then school then exam, then the rest`() {
        val day = listOf(
            event(EventSource.HOLIDAY, "2026-10-10T00:00"),
            event(EventSource.SCHOOL, "2026-10-10T00:00"),
            event(EventSource.MOODLE, "2026-10-10T23:59"),
            event(EventSource.MOODLE, "2026-10-10T12:00"),
        )
        assertEquals(listOf(EventSource.MOODLE, EventSource.SCHOOL, EventSource.HOLIDAY), dotSources(day))
    }

    @Test
    fun `a day shows at most three dots`() {
        val day = EventSource.entries.map { event(it, "2026-10-10T00:00") }
        assertEquals(listOf(EventSource.MOODLE, EventSource.SCHOOL, EventSource.EXAM), dotSources(day))
    }

    @Test
    fun `a day with no events has no dots`() {
        assertEquals(emptyList<EventSource>(), dotSources(emptyList()))
    }

    // --- the date over the day's events ---------------------------------------------------------

    @Test
    fun `the header is the date in the locale's month-day form, marked when it is today`() {
        val today = LocalDate.of(2026, 10, 14)
        assertEquals("10月14日 (今日)", dayTitle(today, today, Locale.TAIWAN, " (今日)"))
        assertEquals("10月15日", dayTitle(today.plusDays(1), today, Locale.TAIWAN, " (今日)"))
        assertEquals("Oct 14 (Today)", dayTitle(today, today, Locale.US, " (Today)"))
    }

    @Test
    fun `the space before the suffix survives the resource losing it`() {
        val today = LocalDate.of(2026, 10, 14)
        // aapt trims the leading space of " (Today)", so the resource reads "(Today)".
        assertEquals("Oct 14 (Today)", dayTitle(today, today, Locale.US, "(Today)"))
        // Japanese brackets are full-width and take no space before them, as on the iPhone.
        assertEquals("10月14日（今日）", dayTitle(today, today, Locale.JAPAN, "（今日）"))
    }

    @Test
    fun `exactly one space before the suffix, before and after the generator keeps edge spaces`() {
        val today = LocalDate.of(2026, 10, 14)
        // Today's resource has lost its leading space; once app-translation#21 ships it keeps it.
        listOf("(Today)", " (Today)").forEach { suffix ->
            assertEquals("'$suffix'", "Oct 14 (Today)", dayTitle(today, today, Locale.US, suffix))
        }
        listOf("(今日)", " (今日)").forEach { suffix ->
            assertEquals("'$suffix'", "10月14日 (今日)", dayTitle(today, today, Locale.TAIWAN, suffix))
        }
    }

    // --- what TalkBack reads for a day cell ----------------------------------------------------

    @Test
    fun `a day cell reads its full date and its number of events`() {
        val day = LocalDate.of(2026, 10, 14)
        assertEquals(
            "Wednesday, October 14, 2026, 2 events",
            dayCellDescription("%1\$@, %2\$lld events", day, 2, Locale.US),
        )
        assertEquals(
            "2026年10月14日 星期三，共 0 個事件",
            dayCellDescription("%1\$@，共 %2\$lld 個事件", day, 0, Locale.TAIWAN),
        )
    }
}
