package org.ntust.app.tigerduck.emptyclassroom

import org.ntust.app.tigerduck.shared.timeline.ClassSlot
import org.ntust.app.tigerduck.shared.timeline.CourseChange
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate

/** One of the user's classes on the day in question, by period index (0 is period 1, 13 is D). */
data class ClassStop(
    val course: String,
    /** The room as the class table shows it. */
    val room: String,
    val place: RoomPlace?,
    val firstPeriod: Int,
    val lastPeriod: Int,
) {
    companion object {
        /**
         * The stops of [courses]' weekly timetable on [weekday] (Mon=1 … Sun=7), as [stops] gives
         * them for a day the school changed nothing.
         */
        fun stops(courses: List<Course>, weekday: Int): List<ClassStop> =
            // Without changes only the weekday counts, so any date on it will do.
            stops(CourseMeetings.buildSlots(courses, ANY_MONDAY.plusDays(weekday - 1L), CourseChanges.EMPTY))

        /**
         * One stop per run of neighbouring periods a class of [slots] meets in the same room, each
         * period's room resolved as the class table resolves it. A course that moves room between
         * neighbouring periods is two stops, so the walk after it starts from the room it ends in.
         *
         * [slots] are the day's classes as the school changed them ([CourseMeetings.meetingSlots]),
         * so a cancelled class is no stop and a room the school moved a class to is its stop's. A
         * class moved to other hours meets at the periods those hours overlap, which its rooms
         * share in their order, each room at least one (tigerduck-app#904). As iOS
         * `ClassStop.stops(from:rawRoom:)`.
         */
        fun stops(slots: List<ClassSlot>): List<ClassStop> = slots.flatMap { slot ->
            val runs = mutableListOf<MutableList<Int>>()
            for (position in slot.periods.indices) {
                val last = runs.lastOrNull()
                if (last != null && roomAt(slot, last.last()) == roomAt(slot, position)) {
                    last += position
                } else {
                    runs += mutableListOf(position)
                }
            }
            val hours = if (slot.hasChangedTime) EmptyClassroomSchedule.periods(slot.start, slot.end) else null
            runs.mapNotNull { run ->
                val periods = if (hours == null) {
                    run.mapNotNull { EmptyClassroomSchedule.periodIndex(slot.periods[it]) }
                } else {
                    share(hours, run.first(), run.last(), slot.periods.size)
                }
                if (periods.isEmpty()) return@mapNotNull null
                val room = roomAt(slot, run.first())
                ClassStop(
                    course = slot.course.displayName,
                    room = room,
                    place = RoomPlace.of(room),
                    firstPeriod = periods.min(),
                    lastPeriod = periods.max(),
                )
            }
        }.sortedBy { it.firstPeriod }

        /** A Monday, for [stops] of a weekday. */
        private val ANY_MONDAY: LocalDate = LocalDate.of(2026, 1, 5)

        /**
         * The share of [hours] for the room run at positions [first] to [last] of a class's
         * [count] timetable periods. Rounding keeps each run at least one hour, even when the
         * class now holds fewer hours than rooms (tigerduck-app#904).
         */
        private fun share(hours: List<Int>, first: Int, last: Int, count: Int): List<Int> {
            if (hours.isEmpty()) return emptyList()
            val low = first * hours.size / count
            val high = maxOf(low, ((last + 1) * hours.size - 1) / count)
            return hours.subList(low, high + 1)
        }

        /** The first room of the class at [position] of its periods: where the school moved it, else the class table's. */
        private fun roomAt(slot: ClassSlot, position: Int): String {
            val period = slot.periods[position]
            val shown = slot.changes
                .firstOrNull { it.kind == CourseChange.Kind.ROOM && it.covers(period) }?.room
                ?: slot.course.classroom(slot.weekday, period)
            return Course.splitRooms(shown).firstOrNull() ?: shown
        }
    }
}

/**
 * The rooms to sit in between the user's classes: free at the chosen period, close to the class
 * before and the class after, and best of all free until the next class starts.
 */
data class RoomSuggestion(
    val period: Int,
    val previous: ClassStop?,
    val next: ClassStop?,
    /** Best first. */
    val candidates: List<Candidate>,
) {
    data class Candidate(
        val room: RoomUsage,
        val place: RoomPlace?,
        /** The last period of the room's free run from the chosen period. */
        val freeUntil: Int,
        val fits: Boolean,
        val seconds: Double,
    )

    /** The periods the card shows the room free for, ending before the next class. */
    fun freeSpan(candidate: Candidate): IntRange {
        val end = next?.let { minOf(candidate.freeUntil, it.firstPeriod - 1) } ?: candidate.freeUntil
        return period..maxOf(period, end)
    }

    companion object {
        /**
         * Null while the user is in class at [period], with no class that day before or after
         * it, when neither class has a room the walking model knows, or when no room is free.
         */
        fun make(rooms: List<RoomUsage>, period: Int, classes: List<ClassStop>, limit: Int = 3): RoomSuggestion? {
            if (classes.any { it.firstPeriod <= period && period <= it.lastPeriod }) return null
            val previous = classes.filter { it.lastPeriod < period }.maxByOrNull { it.lastPeriod }
            val next = classes.filter { it.firstPeriod > period }.minByOrNull { it.firstPeriod }
            val origins = listOfNotNull(previous?.place, next?.place).filter(CampusWalk::hasLocation)
            if (origins.isEmpty()) return null

            val ranked = rooms.mapNotNull { room ->
                val freeUntil = room.freeUntil(period) ?: return@mapNotNull null
                val place = RoomPlace.of(room.room)
                val seconds = place?.let {
                    walk(previous?.place) { from -> CampusWalk.seconds(from, it) } +
                        walk(next?.place) { to -> CampusWalk.seconds(it, to) }
                } ?: Double.POSITIVE_INFINITY
                val fits = next?.let { freeUntil >= it.firstPeriod - 1 } ?: false
                Candidate(room, place, freeUntil, fits, seconds)
            }.sortedWith(
                compareByDescending<Candidate> { it.fits }
                    .thenBy { it.seconds }
                    .thenByDescending { it.freeUntil }
                    .thenBy { it.room.room },
            )
            if (ranked.isEmpty()) return null
            return RoomSuggestion(period, previous, next, ranked.take(limit))
        }

        /**
         * No walk without a class or from one the model cannot place. A class it places but whose
         * walk to the room it cannot measure puts the room after every room of the same fit whose
         * walk it can.
         */
        private fun walk(stop: RoomPlace?, seconds: (RoomPlace) -> Double?): Double {
            if (stop == null || !CampusWalk.hasLocation(stop)) return 0.0
            return seconds(stop) ?: Double.POSITIVE_INFINITY
        }
    }
}
