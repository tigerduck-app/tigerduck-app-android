package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.shared.Course

/** A class table block's time and bottom lines under the school's changes, as on iOS. */
class ClassTableBlockLineTest {

    private val roomless: Course = Course.fromSchedule(
        courseNo = "CC900", courseName = "Compilers", classroom = "", schedule = mapOf(4 to listOf("2", "3", "4")),
    )

    private fun meeting(course: Course, vararg rows: org.json.JSONObject): ClassSlot =
        CourseMeetings.meeting(course, listOf("2", "3", "4"), CourseChangeFixture.thursday, CourseChangeFixture.changes(*rows))!!

    @Test
    fun `a moved room shows where rooms show, though the timetable named none`() {
        val moved = meeting(roomless, row(1, "room", values = mapOf("room" to "TR-214")))
        assertEquals(BlockLine("TR-214", moved = true, cancelled = false), blockBottomLine(moved, null, true, "Cancelled"))
        assertNull(blockBottomLine(moved, null, false, "Cancelled"))
    }

    @Test
    fun `a cancelled class says so whether or not rooms show`() {
        val cancelled = meeting(CourseChangeFixture.course(listOf("2", "3", "4")), row(1, "cancelled"))
        assertEquals(BlockLine("Cancelled", moved = false, cancelled = true), blockBottomLine(cancelled, null, false, "Cancelled"))
        assertEquals(BlockLine("Cancelled", moved = false, cancelled = true), blockBottomLine(cancelled, "T4-501", true, "Cancelled"))
    }

    @Test
    fun `an unchanged block keeps its room hint`() {
        assertEquals(BlockLine("T4-501", moved = false, cancelled = false), blockBottomLine(null, "T4-501", true, "Cancelled"))
        assertNull(blockBottomLine(null, null, false, "Cancelled"))
    }

    @Test
    fun `a block whose inner end moved shows both its ends`() {
        val split = CourseChangeFixture.course(listOf("4", "6"))
        val changes = CourseChangeFixture.changes(row(1, "end", periods = listOf("4"), values = mapOf("end" to "11:45")))
        val block = CourseMeetings.meeting(split, listOf("4", "6"), CourseChangeFixture.thursday, changes)!!
        val alone = CourseMeetings.meeting(split, listOf("4"), CourseChangeFixture.thursday, changes)!!

        assertEquals("11:20–14:10", changedTimeText(block))
        assertEquals("–11:45", changedTimeText(alone))
    }
}
