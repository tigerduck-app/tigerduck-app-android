package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * The class table prints a room in the corner of a cell only when the room is
 * short enough to survive there. The gate is a pattern match, so these pin
 * both sides of it: the codes that must appear, and the prose NTUST also files
 * under "classroom" that must not. Mirrors iOS `CourseRoomHintTests`.
 */
class CourseRoomHintTest {

    @Test
    fun `short building-and-number codes are previewable`() {
        for (room in listOf("TR-313", "E1-134", "AU-101", "IB-409-1", "E1-170-1", "T4 R 313")) {
            assertTrue("expected $room to be previewable", CourseRoomHint.isShortCode(room))
        }
    }

    /** The co-listed NTU / NTNU rooms write the same thing in Chinese. */
    @Test
    fun `Chinese building-and-number rooms are previewable`() {
        for (room in listOf(
            "共101", "博雅205", "管二304", "人文B106", "電二143",
            "霖研一1501", "人文B114-1", "農化二B10-1", "文16",
        )) {
            assertTrue("expected $room to be previewable", CourseRoomHint.isShortCode(room))
        }
    }

    @Test
    fun `rooms that would not fit the cell are dropped`() {
        for (room in listOf(
            "",                                // no room recorded
            "Heping Cheng 101",                // NTNU prose form
            "Gongguan Track and Field Ground",
            "系上自行安排",                      // "ask the department", no number
            "林一",                             // a plot of forest, not a room
            "體育-游泳池",                       // AA-999 only if the class were Unicode-wide
            "綜合-大講堂",                       // same shape, same reason
            "綜合大講堂",                        // facility name, no number to anchor on
            "115研討室",                        // number first, then prose
            "IB-1006",                         // four-digit room
            "TR-313, TR-409",                  // two rooms in one slot
        )) {
            assertFalse("expected $room to be dropped", CourseRoomHint.isShortCode(room))
        }
    }

    /**
     * A per-slot room wins over the day-level aggregate, so a course meeting
     * twice on one day in two rooms labels each block with its own room.
     */
    @Test
    fun `the slot's own room is used`() {
        val course = Course.fromSchedule(
            courseNo = "TEST0001",
            courseName = "Test",
            classroom = "TR-313, TR-409",
            schedule = mapOf(1 to listOf("3", "4"), 3 to listOf("6")),
            classroomMap = mapOf("1-3" to "TR-313", "1-4" to "TR-313", "3-6" to "TR-409"),
        )
        assertEquals("TR-313", CourseRoomHint.room(course, 1, "3"))
        assertEquals("TR-409", CourseRoomHint.room(course, 3, "6"))
        assertNull(CourseRoomHint.room(course, 5, "2"))
    }

    /**
     * A slot the map skips is a slot whose room the portal never gave. The
     * day's other block must not lend it one: labelling a block with the
     * wrong room is worse than labelling it with nothing.
     */
    @Test
    fun `an unmapped slot does not borrow the other block's room`() {
        val course = Course.fromSchedule(
            courseNo = "TEST0002",
            courseName = "Test",
            classroom = "TR-313",
            schedule = mapOf(1 to listOf("3", "4", "6", "7")),
            classroomMap = mapOf("1-3" to "TR-313", "1-4" to "TR-313"),
        )
        assertEquals("TR-313", CourseRoomHint.room(course, 1, "3"))
        assertNull(CourseRoomHint.room(course, 1, "6"))
    }

    /** A course with no per-slot map at all still falls back to its flat room. */
    @Test
    fun `a course with no map falls back to its flat classroom`() {
        val course = Course.fromSchedule(
            courseNo = "TEST0003",
            courseName = "Test",
            classroom = "TR-313",
            schedule = mapOf(1 to listOf("3")),
        )
        assertEquals("TR-313", CourseRoomHint.room(course, 1, "3"))
    }
}
