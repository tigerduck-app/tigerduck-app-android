package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChange.Kind
import java.time.LocalDate

class WatchCourseChangesTest {

    private val day = LocalDate.of(2026, 11, 5)

    @Test
    fun `none sent is none for the timeline`() {
        assertTrue(WatchCourseChanges.EMPTY.toCourseChanges().days("CC900").isEmpty())
    }

    @Test
    fun `each class becomes the rows of its changes over its own periods`() {
        val changes = WatchCourseChanges(
            mapOf(
                "CC900" to mapOf(
                    day to listOf(
                        WatchClassChange(listOf("2"), cancelled = true),
                        WatchClassChange(listOf("3", "4"), room = "TR-411", start = "10:30", end = "12:00"),
                        WatchClassChange(listOf("7")),
                    ),
                ),
            ),
        )
        val rows = changes.toCourseChanges().changes("CC900", day)

        assertEquals(
            listOf(
                Triple(Kind.CANCELLED, listOf("2"), null),
                Triple(Kind.ROOM, listOf("3", "4"), "TR-411"),
                Triple(Kind.START, listOf("3", "4"), "10:30"),
                Triple(Kind.END, listOf("3", "4"), "12:00"),
            ),
            rows.map { Triple(it.kind, it.periods, it.room ?: it.start ?: it.end) },
        )
        assertEquals("every row has an id of its own", rows.size, rows.map { it.id }.distinct().size)
        assertEquals(listOf(day), changes.toCourseChanges().days("CC900").distinct())
        assertTrue(changes.toCourseChanges().changes("CC900", day.plusDays(7)).isEmpty())
        assertTrue(changes.toCourseChanges().changes("OTHER", day).isEmpty())
    }
}
