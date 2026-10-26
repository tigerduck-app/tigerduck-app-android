package org.ntust.app.tigerduck.emptyclassroom

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where a room is, read from its name: the building and the level. */
data class RoomPlace(
    /** The name with case and spaces normalized, which tells two rooms apart. */
    val room: String,
    /** The building code, or for a name with no code, the text before its floor. */
    val building: String,
    /** Floor n is level n, and basement k is level 1 - k, so B1 is 0. */
    val level: Int,
    /** The room's number, which orders the rooms of a floor. */
    val number: Int?,
) {
    /** "2F" or "B1": a standard form every language shows as is. */
    val floorLabel: String get() = floorLabel(level)

    companion object {
        private val huaxia = Regex("""HHC-([A-Z])(\d{3,4}).*""")
        private val coded = Regex("""([A-Z]{1,3}\d?)-(.+)""")
        private val chineseFloor = Regex("""(\d+)樓""")
        private val basementOnly = Regex("""B(\d)""")
        private val basementRoom = Regex("""B(\d)(\d{2}).*""")
        private val basementShort = Regex("""B(\d{2}).*""")
        private val floorOnly = Regex("""(\d+)F""")
        private val numbered = Regex("""(\d{3,4}).*""")

        /**
         * Reads `TR-214`, `TR-310-1`, `IB-1006`, `IB-9F`, `EE-B03`, `T4-B1` and `HHC-A304`, where
         * the hundreds give the floor and 0 means B1, and a name that writes its floor in
         * characters. Null for a name with no floor, such as a placeholder for a room to be set.
         */
        fun floorLabel(level: Int): String = if (level >= 1) "${level}F" else "B${1 - level}"

        fun of(name: String): RoomPlace? {
            val room = name.uppercase().filterNot { it.isWhitespace() }
            huaxia.matchEntire(room)?.let { match ->
                val number = match.groupValues[2].toIntOrNull() ?: return null
                return RoomPlace(room, "HHC-${match.groupValues[1]}", number / 100, number)
            }
            coded.matchEntire(room)?.let { match ->
                val (level, number) = floor(match.groupValues[2]) ?: return null
                return RoomPlace(room, match.groupValues[1], level, number)
            }
            chineseFloor.find(room)?.let { match ->
                val level = match.groupValues[1].toIntOrNull() ?: return null
                return RoomPlace(room, room.substring(0, match.range.first), level, null)
            }
            return null
        }

        /**
         * The part after the building code. A basement written `B03` is room 03 of B1; anything
         * after the digits, such as `-1` or `A`, names part of a room on the same floor.
         */
        private fun floor(rest: String): Pair<Int, Int?>? {
            // The digit runs are bounded but for a whole floor's, which a name of many digits
            // would overflow; such a name has no floor rather than failing the whole list.
            basementOnly.matchEntire(rest)?.let { return it.groupValues[1].toIntOrNull()?.let { depth -> 1 - depth to null } }
            basementRoom.matchEntire(rest)?.let {
                val depth = it.groupValues[1].toIntOrNull() ?: return null
                return 1 - depth to it.groupValues[2].toIntOrNull()
            }
            basementShort.matchEntire(rest)?.let { return 0 to it.groupValues[1].toIntOrNull() }
            floorOnly.matchEntire(rest)?.let { return it.groupValues[1].toIntOrNull()?.let { level -> level to null } }
            numbered.matchEntire(rest)?.let {
                val number = it.groupValues[1].toIntOrNull() ?: return null
                return number / 100 to number
            }
            return null
        }
    }
}

/**
 * The walking model the suggestion ranks rooms by, in seconds. It orders rooms and is not a
 * route: stairs or the lift inside a building, and between buildings the straight line with a
 * detour factor, a minute to get out and in, and the floors down to the ground and up again.
 */
object CampusWalk {
    fun seconds(start: RoomPlace, end: RoomPlace): Double? {
        if (start.room == end.room) return 0.0
        if (start.building == end.building) {
            val floors = abs(start.level - end.level)
            if (floors > 0) return floorSeconds(floors)
            // Below one floor's cost, so a room on the same floor always ranks first.
            val apart = abs((start.number ?: 0) - (end.number ?: 0))
            return 1.0 + min(apart, 9)
        }
        val metres = metres(start.building, end.building) ?: return null
        return 1.3 * metres / 1.2 + 60 + floorSeconds(abs(start.level - 1)) + floorSeconds(abs(end.level - 1))
    }

    /** Stairs up to three floors, the lift beyond. */
    fun floorSeconds(floors: Int): Double = min(15 * floors, 45 + 3 * floors).toDouble()

    fun minutes(seconds: Double): Int = maxOf(1, (seconds / 60).roundToInt())

    fun hasLocation(place: RoomPlace): Boolean = place.building in buildings

    /** Straight-line metres between two buildings by code. */
    fun metres(from: String, to: String): Double? {
        val (fromLat, fromLon) = buildings[from] ?: return null
        val (toLat, toLon) = buildings[to] ?: return null
        val metresPerDegree = 111_320.0
        val latitude = (fromLat + toLat) / 2 * Math.PI / 180
        val east = (toLon - fromLon) * metresPerDegree * cos(latitude)
        val north = (toLat - fromLat) * metresPerDegree
        return sqrt(east * east + north * north)
    }

    /**
     * Building centres on the main campus, in degrees: the booking system's buildings and the
     * others a class meets in. The same table as the iPhone app's.
     */
    private val buildings: Map<String, Pair<Double, Double>> = mapOf(
        "AD" to (25.01323 to 121.54108),
        "AU" to (25.01404 to 121.54165),
        "E1" to (25.01336 to 121.54214),
        "E2" to (25.01253 to 121.54072),
        "EE" to (25.01189 to 121.54137),
        "IA" to (25.01461 to 121.54319),
        "IB" to (25.01304 to 121.54025),
        "LB" to (25.01382 to 121.54121),
        "MA" to (25.01239 to 121.54126),
        "RB" to (25.01423 to 121.54140),
        "T1" to (25.01435 to 121.54178),
        "T2" to (25.01208 to 121.54090),
        "T3" to (25.01350 to 121.54291),
        "T4" to (25.01388 to 121.54177),
        "TR" to (25.01505 to 121.54280),
    )
}

/** The move between two rooms that a route leg draws. */
sealed interface RoomMove {
    data object SameRoom : RoomMove

    /** Floors up when positive, down when negative. */
    data class Floors(val floors: Int) : RoomMove

    data class Walk(val minutes: Int) : RoomMove

    companion object {
        /** Null for another room on the same floor, and where either place is unknown. */
        fun between(start: RoomPlace?, end: RoomPlace?): RoomMove? {
            if (start == null || end == null) return null
            if (start.room == end.room) return SameRoom
            if (start.building == end.building) {
                val floors = end.level - start.level
                return if (floors == 0) null else Floors(floors)
            }
            return CampusWalk.seconds(start, end)?.let { Walk(CampusWalk.minutes(it)) }
        }
    }
}
