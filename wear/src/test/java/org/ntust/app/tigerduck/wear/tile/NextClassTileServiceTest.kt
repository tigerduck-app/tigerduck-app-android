package org.ntust.app.tigerduck.wear.tile

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import java.time.LocalDate

/** When the tile swaps layouts: a class is over from its end minute (start <= now < end). */
class NextClassTileServiceTest {

    private val period4 = Course.fromSchedule("P4", "Period four", schedule = mapOf(2 to listOf("3", "4")))   // 10:20-12:10
    private val afternoon = Course.fromSchedule("P7", "Afternoon", schedule = mapOf(2 to listOf("7", "8")))   // 14:20-16:20

    private val tuesday = LocalDate.of(2026, 11, 3)

    private fun transitions(
        minuteOfDay: Int,
        changes: WatchCourseChanges = WatchCourseChanges.EMPTY,
    ): List<Int> {
        val blocks = NextClassResolver.todaysClasses(listOf(period4, afternoon), tuesday, minuteOfDay, changes = changes)
            .filter { it.endMinute > minuteOfDay }
        return tileTransitionMinutes(blocks, minuteOfDay)
    }

    @Test
    fun `the next transition after 12-09 is 12-10, not 12-11`() {
        assertEquals(listOf(12 * 60 + 10, 14 * 60 + 20, 16 * 60 + 20), transitions(12 * 60 + 9))
    }

    @Test
    fun `a class the school ended early is over at its changed end minute`() {
        val changed = WatchCourseChanges(mapOf("P4" to mapOf(tuesday to listOf(WatchClassChange(listOf("3", "4"), end = "11:45")))))
        assertEquals(listOf(11 * 60 + 45, 14 * 60 + 20, 16 * 60 + 20), transitions(11 * 60, changed))
    }

    @Test
    fun `overlapping classes keep every boundary, earliest first`() {
        // Afternoon, moved to 11:20-13:10, overlaps Period four's 10:20-12:10.
        val moved = WatchCourseChanges(
            mapOf("P7" to mapOf(tuesday to listOf(WatchClassChange(listOf("7", "8"), start = "11:20", end = "13:10")))),
        )
        assertEquals(listOf(11 * 60 + 20, 12 * 60 + 10, 13 * 60 + 10), transitions(11 * 60 + 19, moved))
    }
}
