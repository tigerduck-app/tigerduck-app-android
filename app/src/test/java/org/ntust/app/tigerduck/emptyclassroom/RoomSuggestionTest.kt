package org.ntust.app.tigerduck.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * A made-up Wednesday on the main campus: the user has a class at periods 1-2 in TR-214, at 5-6
 * in TR-309 and at 8-9 in IB-503. Rooms by period: `.` free, `c` a class, `b` booked.
 */
class RoomSuggestionTest {
    companion object {
        private val day = mapOf(
            "TR-209" to "cccc.cc.cc....", "TR-210" to "cc..cc..cc....", "TR-211" to "cccc..cc......",
            "TR-212" to "cc....cc..b...", "TR-213" to "..c.cc..cc....", "TR-214" to "cccccc........",
            "TR-309" to "cc..cc..cc....", "TR-310-1" to "cc............", "TR-311" to "cc..b.cc..cc..",
            "TR-312" to "ccccc..ccc....", "TR-313" to "..cc..cc......", "TR-411" to "..b...cc......",
            "TR-412-1" to "cc.cc.........", "TR-510" to "cccccccc......", "TR-611" to "cc..cccc..cc..",
            "IB-305" to "cc..cc..cc....", "IB-401" to "cccc..cc......", "IB-503" to "cc..cc.cc.....",
            "IB-505" to "..........cccc", "IB-508" to "..............", "IB-602-1" to "cccccc..cc....",
            "T4-301" to "cc..cc........", "T4-302" to "cccccccc......", "T4-402" to "....cc..cc....",
            "T4-404" to "cc.c..........", "T3-301" to "cccc..cc......", "T3-303" to "..cc..........",
            "EE-311" to "cc..cc..cccc..", "EE-406" to "cccc....cc....", "E1-306" to "cccccccccc....",
            "E2-102" to "cc..cc........",
        )

        val rooms: List<RoomUsage> = day.keys.sorted().map { room ->
            RoomUsage(
                room,
                day.getValue(room).map { mark ->
                    when (mark) {
                        '.' -> RoomSlotUse.Free
                        'b' -> RoomSlotUse.Booked
                        else -> RoomSlotUse.Course("課程", "")
                    }
                },
            )
        }

        fun stop(course: String, room: String, periods: IntRange) =
            ClassStop(course, room, RoomPlace.of(room), periods.first, periods.last)

        val classes = listOf(
            stop("計算機網路", "TR-214", 0..1),
            stop("作業系統", "TR-309", 4..5),
            stop("資料庫系統", "IB-503", 7..8),
        )
    }

    @Test
    fun `a free room in a building the walking model cannot place ranks after the ones it can`() {
        val unplaced = RoomUsage("ME1-261", List(14) { RoomSlotUse.Free })
        val suggestion = requireNotNull(RoomSuggestion.make(rooms + unplaced, 2, classes, limit = 40))

        assertEquals("TR-309", suggestion.candidates.first().room.room)
        assertEquals("ME1-261", suggestion.candidates.filter { it.fits }.last().room.room)
    }

    @Test
    fun `a class in a building the walking model cannot place adds no walk, so the other class ranks the rooms`() {
        val classes = listOf(stop("計算機網路", "ME1-201", 0..1), stop("作業系統", "TR-309", 4..5))
        assertEquals("TR-309", requireNotNull(RoomSuggestion.make(rooms, 2, classes)).candidates.first().room.room)
    }

    @Test
    fun `between TR-214 and TR-309 the next class's own room comes first, one floor up and staying put`() {
        val suggestion = requireNotNull(RoomSuggestion.make(rooms, 2, classes))
        val best = suggestion.candidates.first()

        assertEquals(listOf("TR-309", "TR-310-1", "TR-212"), suggestion.candidates.map { it.room.room })
        assertEquals("TR-214", suggestion.previous?.room)
        assertEquals("TR-309", suggestion.next?.room)
        assertEquals(RoomMove.Floors(1), RoomMove.between(suggestion.previous?.place, best.place))
        assertEquals(RoomMove.SameRoom, RoomMove.between(best.place, suggestion.next?.place))
        assertTrue(best.fits)
        assertEquals(2..3, suggestion.freeSpan(best))
    }

    @Test
    fun `a room free until the next class ranks before a closer one taken sooner`() {
        val order = requireNotNull(RoomSuggestion.make(rooms, 2, classes, limit = 100)).candidates
        val names = order.map { it.room.room }
        assertTrue(names.indexOf("TR-611") < names.indexOf("TR-412-1"))
        assertTrue(order.all { it.room.isFree(2) })
    }

    @Test
    fun `the free span stops before the next class even when the room stays free`() {
        val suggestion = requireNotNull(RoomSuggestion.make(rooms, 2, classes, limit = 100))
        val open = suggestion.candidates.first { it.room.room == "TR-310-1" }
        assertEquals(13, open.freeUntil)
        assertEquals(2..3, suggestion.freeSpan(open))
    }

    @Test
    fun `in class there is no suggestion`() {
        assertNull(RoomSuggestion.make(rooms, 4, classes))
        assertNull(RoomSuggestion.make(rooms, 8, classes))
    }

    @Test
    fun `after the last class the rooms near it lead, and the class's own room stays put`() {
        val suggestion = requireNotNull(RoomSuggestion.make(rooms, 9, classes))
        val best = suggestion.candidates.first()

        assertEquals("資料庫系統", suggestion.previous?.course)
        assertNull(suggestion.next)
        assertEquals("IB-503", best.room.room)
        assertEquals(RoomMove.SameRoom, RoomMove.between(suggestion.previous?.place, best.place))
        assertFalse(best.fits)
        assertEquals(9..13, suggestion.freeSpan(best))
    }

    @Test
    fun `before the first class the route leads to it`() {
        val suggestion = requireNotNull(RoomSuggestion.make(rooms, 2, listOf(stop("作業系統", "TR-309", 4..5)), limit = 100))
        val below = suggestion.candidates.first { it.room.room == "TR-212" }

        assertNull(suggestion.previous)
        assertEquals("TR-309", suggestion.candidates.first().room.room)
        assertEquals(RoomMove.Floors(1), RoomMove.between(below.place, suggestion.next?.place))
    }

    @Test
    fun `no class that day, or none with a known room, gives no suggestion`() {
        assertNull(RoomSuggestion.make(rooms, 2, emptyList()))
        val unplaced = listOf(stop("專題研究", "系上安排", 0..1), stop("書報討論", "HHC-A304", 4..5))
        assertNull(RoomSuggestion.make(rooms, 2, unplaced))
    }
}

class BuildingDirectoryTest {
    private val directory = BuildingDirectory.of(RoomSuggestionTest.rooms, 2)

    @Test
    fun `every building with rooms that day, with how many are free at the period`() {
        assertEquals(listOf("E1", "E2", "EE", "IB", "T3", "T4", "TR"), directory.buildings.map { it.code })
        assertEquals(
            mapOf("E1" to 0, "E2" to 1, "EE" to 1, "IB" to 4, "T3" to 0, "T4" to 3, "TR" to 7),
            directory.buildings.associate { it.code to it.free.size },
        )
    }

    @Test
    fun `a building's free rooms by floor, lowest first, in room order`() {
        val floors = directory.floors("TR")
        assertEquals(listOf(2, 3, 4, 6), floors.map { it.level })
        assertEquals(
            listOf(listOf("TR-210", "TR-212"), listOf("TR-309", "TR-310-1", "TR-311"), listOf("TR-412-1"), listOf("TR-611")),
            floors.map { floor -> floor.rooms.map { it.room } },
        )
        assertTrue(directory.floors("T3").isEmpty())
    }

    @Test
    fun `the nearest two other buildings with a free room`() {
        assertEquals(listOf("T4", "TR"), directory.nearby("T3").map { it.code })
        assertEquals(listOf("T4", "E2"), directory.nearby("E1").map { it.code })
    }

    @Test
    fun `the last pick while it has rooms that day, else the suggestion's building, else the most free rooms`() {
        assertEquals("T3", directory.defaultBuilding(picked = "T3", suggested = "IB"))
        assertEquals("IB", directory.defaultBuilding(picked = "MA", suggested = "IB"))
        assertEquals("TR", directory.defaultBuilding(picked = null, suggested = null))
        assertNull(BuildingDirectory.of(emptyList(), 2).defaultBuilding(picked = "TR", suggested = null))
    }
}

class ClassStopTest {
    @Test
    fun `a course that moves room between neighbouring periods is a stop in each room`() {
        val course = Course.fromSchedule(
            courseNo = "CS1001301",
            courseName = "計算機網路",
            classroom = "TR-214, IB-305",
            schedule = mapOf(3 to listOf("1", "2", "3", "4")),
            classroomMap = mapOf("3-1" to "TR-214", "3-2" to "TR-214", "3-3" to "IB-305", "3-4" to "IB-305"),
        )

        val stops = ClassStop.stops(listOf(course), weekday = 3)

        assertEquals(listOf("TR-214", "IB-305"), stops.map { it.room })
        assertEquals(listOf(0, 2), stops.map { it.firstPeriod })
        assertEquals(listOf(1, 3), stops.map { it.lastPeriod })
        // So the walk after period 4 starts from IB-305, not TR-214.
        val suggestion = requireNotNull(
            RoomSuggestion.make(
                listOf(RoomUsage("IB-306", List(14) { RoomSlotUse.Free }), RoomUsage("TR-215", List(14) { RoomSlotUse.Free })),
                period = 4,
                classes = stops,
            ),
        )
        assertEquals("IB-305", suggestion.previous?.room)
        assertEquals("IB-306", suggestion.candidates.first().room.room)
    }

    @Test
    fun `each run of a course is a stop at its periods and the room of its first period`() {
        val course = Course.fromSchedule(
            courseNo = "CS1001301",
            courseName = "計算機網路",
            classroom = "TR-214, TR-309",
            schedule = mapOf(3 to listOf("6", "1", "2")),
            classroomMap = mapOf("3-1" to "TR-214", "3-2" to "TR-214", "3-6" to "TR-309"),
        )
        val other = Course.fromSchedule(courseNo = "GE0001301", courseName = "體育", classroom = "系上安排", schedule = mapOf(1 to listOf("3")))

        val stops = ClassStop.stops(listOf(course, other), weekday = 3)

        assertEquals(listOf("計算機網路", "計算機網路"), stops.map { it.course })
        assertEquals(listOf("TR-214", "TR-309"), stops.map { it.room })
        assertEquals(listOf(2, 3), stops.map { it.place?.level })
        assertEquals(listOf(0, 5), stops.map { it.firstPeriod })
        assertEquals(listOf(1, 5), stops.map { it.lastPeriod })
        assertTrue(ClassStop.stops(listOf(other), weekday = 3).isEmpty())
        assertNull(ClassStop.stops(listOf(other), weekday = 1).single().place)
    }
}
