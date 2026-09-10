package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock NTUST publishes for its 14 teaching periods. Asserted verbatim
 * because every other time in the app — block spans, "next class", the widget
 * timeline — is derived from it, so a single typo here is silently wrong
 * everywhere at once. The iOS app carries the same table and the same test.
 */
class PeriodScheduleTest {

    private val published = listOf(
        Triple("1", "08:10", "09:00"),
        Triple("2", "09:10", "10:00"),
        Triple("3", "10:20", "11:10"),
        Triple("4", "11:20", "12:10"),
        Triple("5", "12:20", "13:10"),
        Triple("6", "13:20", "14:10"),
        Triple("7", "14:20", "15:10"),
        Triple("8", "15:30", "16:20"),
        Triple("9", "16:30", "17:20"),
        Triple("10", "17:30", "18:20"),
        Triple("A", "18:25", "19:15"),
        Triple("B", "19:20", "20:10"),
        Triple("C", "20:15", "21:05"),
        Triple("D", "21:10", "22:00"),
    )

    @Test
    fun `period times match the published timetable`() {
        published.forEach { (id, start, end) ->
            assertEquals("period $id", start to end, PeriodTimes.mapping[id])
        }
        assertEquals(published.size, PeriodTimes.mapping.size)
    }

    @Test
    fun `chronological order is actually chronological`() {
        val minutes = Periods.chronologicalOrder.map { id ->
            val times = requireNotNull(PeriodTimes.mapping[id]) { "period $id has no time" }
            parseHm(times.first)!! to parseHm(times.second)!!
        }
        minutes.zipWithNext { previous, current ->
            assertTrue("period ends before it starts", previous.first < previous.second)
            assertTrue("periods overlap or run backwards", previous.second <= current.first)
        }
    }

    /** The toggle unions in the whole clock, so the default rows must be part of it. */
    @Test
    fun `default visible periods are a subset of the chronological order`() {
        assertTrue(Periods.chronologicalOrder.containsAll(Periods.defaultVisible))
    }
}
