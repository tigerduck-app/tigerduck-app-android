package org.ntust.app.tigerduck.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoomPlaceTest {
    private fun place(room: String) = requireNotNull(RoomPlace.of(room)) { "no place for $room" }

    @Test
    fun `the hundreds of a room number give the floor, and 0 means B1`() {
        assertEquals("TR", place("TR-214").building)
        assertEquals(2, place("TR-214").level)
        assertEquals(3, place("TR-309").level)
        assertEquals(0, place("LB-001").level)
        assertEquals(0, place("MA-010").level)
        assertEquals(10, place("IB-1006").level)
        assertEquals(4, place("T2-405-3").level)
    }

    @Test
    fun `a part of a room stays on the room's floor`() {
        assertEquals(3, place("TR-310-1").level)
        assertEquals(310, place("TR-310-1").number)
        assertEquals(8, place("RB-809A").level)
        assertEquals(7, place("RB-708(C1)(C4)").level)
        assertNotEquals(place("TR-310-1"), place("TR-310-2"))
    }

    @Test
    fun `basements, whole floors, the other campus and a floor written in characters`() {
        assertEquals(0, place("T4-B1").level)
        assertEquals(0, place("EE-B03").level)
        assertEquals("EE", place("EE-B03").building)
        assertEquals(-1, place("XY-B205").level)
        assertEquals(9, place("IB-9F").level)
        assertEquals("HHC-A", place("HHC-A304").building)
        assertEquals(3, place("HHC-A304").level)
        assertEquals(2, place("華夏體育館2樓桌球室").level)
        assertEquals(place("TR-214"), place(" tr-214 "))
    }

    @Test
    fun `a placeholder for a room to be set has no place`() {
        assertNull(RoomPlace.of("系上安排"))
        assertNull(RoomPlace.of(""))
        assertNull(RoomPlace.of("TR-"))
        assertNull(RoomPlace.of("T3 Cafeteria"))
        // A whole floor written with more digits than an Int holds is no floor, not a crash.
        assertNull(RoomPlace.of("IB-99999999999F"))
    }
}

class CampusWalkTest {
    private fun seconds(from: String, to: String): Double =
        requireNotNull(CampusWalk.seconds(requireNotNull(RoomPlace.of(from)), requireNotNull(RoomPlace.of(to))))

    @Test
    fun `the same room, then the same floor, then upstairs, then other buildings by distance`() {
        val costs = listOf("TR-214", "TR-209", "TR-309", "TR-614", "T4-301", "IB-305", "EE-311").map { seconds("TR-214", it) }
        assertEquals(costs.sorted(), costs)
        assertEquals(costs.size, costs.toSet().size)
        assertEquals(0.0, costs[0], 0.0)
    }

    @Test
    fun `stairs up to three floors, the lift beyond`() {
        assertEquals(45.0, seconds("TR-214", "TR-509"), 0.0)
        assertEquals(63.0, seconds("TR-309", "TR-912"), 0.0)
        assertEquals(15.0, CampusWalk.floorSeconds(1), 0.0)
        assertEquals(57.0, CampusWalk.floorSeconds(4), 0.0)
    }

    @Test
    fun `between buildings the walk adds a minute and the floors down and up again`() {
        val neighbours = seconds("E2-102", "IB-101")
        val across = seconds("TR-309", "IB-305")
        assertTrue("got $neighbours", abs(neighbours - 140) < 5)
        assertTrue("got $across", abs(across - 489) < 10)
        assertEquals(across, seconds("IB-305", "TR-309"), 1e-9)
    }

    @Test
    fun `a building the model has no position for has no walk to another`() {
        val start = requireNotNull(RoomPlace.of("HHC-A304"))
        val end = requireNotNull(RoomPlace.of("TR-214"))
        assertNull(CampusWalk.seconds(start, end))
        assertFalse(CampusWalk.hasLocation(start))
        assertTrue(CampusWalk.hasLocation(end))
    }

    @Test
    fun `a walk rounds to whole minutes, at least one`() {
        assertEquals(1, CampusWalk.minutes(20.0))
        assertEquals(1, CampusWalk.minutes(89.0))
        assertEquals(2, CampusWalk.minutes(91.0))
        assertEquals(8, CampusWalk.minutes(489.0))
    }

    @Test
    fun `the move a route leg draws between two rooms`() {
        fun move(from: String, to: String) = RoomMove.between(RoomPlace.of(from), RoomPlace.of(to))
        assertEquals(RoomMove.SameRoom, move("TR-309", "TR-309"))
        assertEquals(RoomMove.Floors(1), move("TR-214", "TR-309"))
        assertEquals(RoomMove.Floors(-4), move("TR-614", "TR-214"))
        assertNull(move("TR-212", "TR-214"))
        assertEquals(RoomMove.Walk(8), move("TR-309", "IB-305"))
        assertNull(move("HHC-A304", "TR-214"))
        assertNull(move("系上安排", "TR-214"))
    }
}
