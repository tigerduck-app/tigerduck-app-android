package org.ntust.app.tigerduck.emptyclassroom

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate

/**
 * The user's classes the finder walks between, on a Wednesday the school changed them. Ported
 * from the iPhone's `ClassStopTests`; course numbers are made up.
 */
class ClassStopChangesTest {
    private val wednesday: LocalDate = LocalDate.of(2026, 10, 14)

    private fun row(id: Int, courseNo: String, kind: String, periods: List<String>? = null, values: Map<String, String>) =
        CourseChangeFixture.row(id, kind, periods, days = listOf("2026-10-14"), values = values, courseNo = courseNo)

    private fun changes(vararg rows: JSONObject): CourseChanges = CourseChanges.decode(CourseChangeFixture.feed(rows.toList()))

    private fun stops(course: Course, changes: CourseChanges): List<ClassStop> =
        ClassStop.stops(CourseMeetings.meetingSlots(listOf(course), wednesday, changes, isClassDay = { true }))

    /** Periods 1 to 4 on Wednesday, 1-2 in TR-214 and 3-4 in IB-305. */
    private fun twoRooms(courseNo: String) = Course.fromSchedule(
        courseNo = courseNo,
        courseName = "作業系統",
        classroom = "TR-214, IB-305",
        schedule = mapOf(3 to listOf("1", "2", "3", "4")),
        classroomMap = mapOf("3-1" to "TR-214", "3-2" to "TR-214", "3-3" to "IB-305", "3-4" to "IB-305"),
    )

    @Test
    fun `a moved block stops in its new room`() {
        val course = Course.fromSchedule(courseNo = "CC903", courseName = "編譯器", classroom = "TR-214", schedule = mapOf(3 to listOf("1", "2", "6")))
        val changes = changes(row(1, "CC903", "room", periods = listOf("1", "2"), values = mapOf("room" to "IB-305")))

        val stops = stops(course, changes)

        assertEquals(listOf("IB-305", "TR-214"), stops.map { it.room })
        assertEquals(listOf(0, 5), stops.map { it.firstPeriod })
    }

    @Test
    fun `a retimed block stops at its new periods`() {
        val course = Course.fromSchedule(courseNo = "CC904", courseName = "資料庫", classroom = "TR-214", schedule = mapOf(3 to listOf("8")))
        val changes = changes(
            row(1, "CC904", "start", values = mapOf("start" to "09:10")),
            row(2, "CC904", "end", values = mapOf("end" to "10:00")),
        )

        val stops = stops(course, changes)

        assertEquals(listOf("TR-214"), stops.map { it.room })
        assertEquals(listOf(1), stops.map { it.firstPeriod })
        assertEquals(listOf(1), stops.map { it.lastPeriod })
    }

    @Test
    fun `a retimed block keeps its rooms`() {
        val changes = changes(
            row(1, "CC905", "start", values = mapOf("start" to "13:20")),
            row(2, "CC905", "end", values = mapOf("end" to "17:20")),
        )

        val stops = stops(twoRooms("CC905"), changes)

        assertEquals(listOf("TR-214", "IB-305"), stops.map { it.room })
        assertEquals(listOf(5, 7), stops.map { it.firstPeriod })
        assertEquals(listOf(6, 8), stops.map { it.lastPeriod })
    }

    @Test
    fun `a shortened block keeps every room`() {
        // tigerduck-app#904: one hour for two rooms still gives each room a stop.
        val changes = changes(
            row(1, "CC906", "start", values = mapOf("start" to "09:10")),
            row(2, "CC906", "end", values = mapOf("end" to "10:00")),
        )

        val stops = stops(twoRooms("CC906"), changes)

        assertEquals(listOf("TR-214", "IB-305"), stops.map { it.room })
        assertEquals(listOf(1, 1), stops.map { it.firstPeriod })
        assertEquals(listOf(1, 1), stops.map { it.lastPeriod })
    }

    @Test
    fun `a cancelled block is no stop while the rest of the day's are`() {
        val course = Course.fromSchedule(courseNo = "CC907", courseName = "編譯器", classroom = "TR-214", schedule = mapOf(3 to listOf("1", "2", "6")))
        val changes = changes(row(1, "CC907", "cancelled", periods = listOf("1", "2"), values = emptyMap()))

        val stops = stops(course, changes)

        assertEquals(listOf("TR-214"), stops.map { it.room })
        assertEquals(listOf(5), stops.map { it.firstPeriod })
    }

    @Test
    fun `with no changes a day's stops are its weekday's`() {
        val course = Course.fromSchedule(
            courseNo = "CC908",
            courseName = "計算機網路",
            classroom = "TR-214, TR-309",
            schedule = mapOf(3 to listOf("6", "1", "2", "3", "4")),
            classroomMap = mapOf("3-1" to "TR-214", "3-2" to "TR-214", "3-3" to "IB-305", "3-4" to "IB-305", "3-6" to "TR-309"),
        )

        assertEquals(ClassStop.stops(listOf(course), weekday = 3), stops(course, CourseChanges.EMPTY))
    }
}
