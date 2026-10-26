package org.ntust.app.tigerduck.emptyclassroom

/**
 * The building section for a day and period: every building with rooms that day, built from the
 * rooms returned rather than the site's list, which also names buildings without rooms in the
 * booking system.
 */
data class BuildingDirectory(val buildings: List<Building>) {
    data class Building(
        val code: String,
        /** The building's rooms free at the period. */
        val free: List<RoomUsage>,
    )

    data class Floor(val level: Int, val rooms: List<RoomUsage>) {
        val label: String get() = RoomPlace.floorLabel(level)
    }

    /** The building's free rooms by floor, lowest first, each floor in room order. */
    fun floors(code: String): List<Floor> {
        val building = buildings.firstOrNull { it.code == code } ?: return emptyList()
        return building.free
            .map { it to RoomPlace.of(it.room) }
            .groupBy { (_, place) -> place?.level ?: 1 }
            .toSortedMap()
            .map { (level, rooms) ->
                Floor(
                    level,
                    rooms.sortedWith(
                        compareBy<Pair<RoomUsage, RoomPlace?>> { it.second?.number ?: 0 }
                            .thenBy { it.first.room },
                    ).map { it.first },
                )
            }
    }

    /** Up to [limit] other buildings with a free room, nearest first. */
    fun nearby(code: String, limit: Int = 2): List<Building> = buildings
        .filter { it.code != code && it.free.isNotEmpty() }
        .sortedWith(
            compareBy<Building> { CampusWalk.metres(code, it.code) ?: Double.POSITIVE_INFINITY }
                .thenBy { it.code },
        )
        .take(limit)

    /**
     * The user's last pick while it has rooms that day, else the suggested room's building, else
     * the building with the most free rooms.
     */
    fun defaultBuilding(picked: String?, suggested: String?): String? {
        val codes = buildings.map { it.code }.toSet()
        if (picked != null && picked in codes) return picked
        if (suggested != null && suggested in codes) return suggested
        return buildings.sortedWith(compareByDescending<Building> { it.free.size }.thenBy { it.code })
            .firstOrNull()?.code
    }

    companion object {
        fun of(rooms: List<RoomUsage>, period: Int): BuildingDirectory {
            val byBuilding = rooms.groupBy { RoomPlace.of(it.room)?.building ?: it.room }
            return BuildingDirectory(
                byBuilding.keys.sorted().map { code ->
                    Building(code, byBuilding.getValue(code).filter { it.isFree(period) })
                },
            )
        }
    }
}
