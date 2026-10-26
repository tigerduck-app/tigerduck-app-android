package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

class WidgetScheduleCellsTest {

    private fun course(no: String, periods: List<String>) = Course(
        courseNo = no,
        courseName = "Course $no",
        scheduleJson = "{\"1\":[" + periods.joinToString(",") { "\"$it\"" } + "]}",
    )

    @Test
    fun `a course whose two blocks another course bridges keeps both`() {
        // A meets on 1-2 and 4-5, B on 2-4: one cluster down to period 5, with A's second block
        // in it (https://github.com/tigerduck-app/tigerduck-app/issues/470).
        val courses = listOf(course("A", listOf("1", "2", "4", "5")), course("B", listOf("2", "3", "4")))

        val cells = buildScheduleCells(listOf("1", "2", "3", "4", "5"), courses, weekday = 1)

        assertEquals(1, cells.size)
        val cluster = cells.single() as ScheduleCell.MultiConflict
        assertEquals(5, cluster.combinedSpan)
        assertEquals(
            listOf(Triple("A", 0, 2), Triple("B", 1, 3), Triple("A", 3, 2)),
            cluster.members.map { Triple(it.course.courseNo, it.offset, it.span) },
        )
    }
}
