package org.ntust.app.tigerduck.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * The calendar's day list shows no time on a row that is a whole day: "00:00" said nothing,
 * and on a busy day read as a midnight deadline. Same rule as the iPhone's
 * `SDCalendarEvent.isAllDay`.
 */
class CalendarEventAllDayTest {

    private fun at(source: EventSource, dateTime: String, zone: String = "Asia/Taipei") =
        CalendarEvent(
            eventId = "e",
            title = "t",
            date = Date.from(LocalDateTime.parse(dateTime).atZone(ZoneId.of(zone)).toInstant()),
            sourceRaw = source.raw,
        )

    @Test
    fun `holidays and term boundaries are whole days whatever time they carry`() {
        assertTrue(at(EventSource.HOLIDAY, "2026-10-10T00:00").isAllDay)
        assertTrue(at(EventSource.SEMESTER, "2026-09-07T00:00").isAllDay)
        assertTrue(at(EventSource.HOLIDAY, "2026-10-10T08:00").isAllDay)
    }

    @Test
    fun `a school row is a whole day when the feed gave a date with no time`() {
        // CalendarService parses a date-only ICS value to midnight in Taipei.
        assertTrue(at(EventSource.SCHOOL, "2026-11-02T00:00").isAllDay)
        assertFalse(at(EventSource.SCHOOL, "2026-11-02T09:30").isAllDay)
    }

    @Test
    fun `midnight is read in Taipei, not in the phone's zone`() {
        // Midnight in UTC is 08:00 in Taipei: a real time, so it keeps it.
        assertFalse(at(EventSource.SCHOOL, "2026-11-02T00:00", zone = "UTC").isAllDay)
    }

    @Test
    fun `a Moodle deadline at midnight is a real deadline and keeps its time`() {
        assertFalse(at(EventSource.MOODLE, "2026-11-02T00:00").isAllDay)
        assertFalse(at(EventSource.EXAM, "2026-11-02T00:00").isAllDay)
    }
}
