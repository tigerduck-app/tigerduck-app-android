package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChange.Kind
import java.time.LocalDate

class WatchCourseChangesTest {

    private val day = LocalDate.of(2026, 11, 5)

    // Thursday: 2-3-4 and 7.
    private val course = Course.fromSchedule("CC900", "Fake", schedule = mapOf(4 to listOf("2", "3", "4", "7")))

    @Test
    fun `none sent is none for the timeline`() {
        assertTrue(WatchCourseChanges.EMPTY.toCourseChanges(listOf(course)).days("CC900").isEmpty())
    }

    @Test
    fun `each class becomes the rows of its changes over its own periods`() {
        val changes = WatchCourseChanges(
            mapOf(
                "CC900" to mapOf(
                    day to listOf(
                        WatchClassChange(listOf("2"), cancelled = true),
                        WatchClassChange(listOf("3"), room = "TR-411", start = "10:30", end = "12:00"),
                        WatchClassChange(listOf("7")),
                    ),
                ),
            ),
        )
        val rows = changes.toCourseChanges(listOf(course)).changes("CC900", day)

        assertEquals(
            listOf(
                Triple(Kind.CANCELLED, listOf("2"), null),
                Triple(Kind.ROOM, listOf("3"), "TR-411"),
                Triple(Kind.START, listOf("3"), "10:30"),
                Triple(Kind.END, listOf("3"), "12:00"),
                // Period 4 is in no sent class: the phone left out a piece, and it stays out.
                Triple(Kind.CANCELLED, listOf("4"), null),
            ),
            rows.map { Triple(it.kind, it.periods, it.room ?: it.start ?: it.end) },
        )
        assertEquals("every row has an id of its own", rows.size, rows.map { it.id }.distinct().size)
        assertEquals(listOf(day), changes.toCourseChanges(listOf(course)).days("CC900").distinct())
        assertTrue(changes.toCourseChanges(listOf(course)).changes("CC900", day.plusDays(7)).isEmpty())
        assertTrue(changes.toCourseChanges(listOf(course)).changes("OTHER", day).isEmpty())
    }
}
