package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.util.Date

/** Home's time slider opens the class table's course detail for the slot tapped, on its day and period. */
class CourseDetailSelectionTest {

    private fun on(day: String): Date =
        Date.from(LocalDate.parse(day).atTime(8, 0).atZone(AppConstants.TAIPEI_ZONE).toInstant())

    @Test
    fun `a slot opens the course detail on its own weekday and first period`() {
        // Split around noon on Wednesday: each run is a slot of its own, with its own room.
        val course = Course.fromSchedule("CS3005301", "Data Structures", schedule = mapOf(1 to listOf("2", "3", "4"), 3 to listOf("3", "4", "6", "7")))
        val wednesday = CourseTimeSlot.buildSlots(listOf(course), weekday = 3, on = on("2026-09-30"))

        assertEquals(
            listOf(CourseDetailSelection(course, 3, "3"), CourseDetailSelection(course, 3, "6")),
            wednesday.map { it.detailSelection },
        )
    }
}
