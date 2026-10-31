package org.ntust.app.tigerduck.wear.tile

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.WatchClassChange

/** When the tile swaps layouts: a class is over from its end minute (start <= now < end). */
class NextClassTileServiceTest {

    private val period4 = Course.fromSchedule("P4", "Period four", schedule = mapOf(2 to listOf("3", "4")))   // 10:20-12:10
    private val afternoon = Course.fromSchedule("P7", "Afternoon", schedule = mapOf(2 to listOf("7", "8")))   // 14:20-16:20

    private fun transitions(
        minuteOfDay: Int,
        changedClasses: (String) -> List<WatchClassChange> = { emptyList() },
    ): List<Int> {
        val blocks = NextClassResolver.todaysClasses(listOf(period4, afternoon), 2, minuteOfDay, changedClasses = changedClasses)
            .filter { it.endMinute > minuteOfDay }
        return tileTransitionMinutes(blocks, minuteOfDay)
    }

    @Test
    fun `the next transition after 12-09 is 12-10, not 12-11`() {
        assertEquals(listOf(12 * 60 + 10, 14 * 60 + 20, 16 * 60 + 20), transitions(12 * 60 + 9))
    }

    @Test
    fun `a class the school ended early is over at its changed end minute`() {
        val changed = { courseNo: String ->
            if (courseNo == "P4") listOf(WatchClassChange(listOf("3", "4"), end = "11:45")) else emptyList()
        }
        assertEquals(listOf(11 * 60 + 45, 14 * 60 + 20, 16 * 60 + 20), transitions(11 * 60, changed))
    }
}
