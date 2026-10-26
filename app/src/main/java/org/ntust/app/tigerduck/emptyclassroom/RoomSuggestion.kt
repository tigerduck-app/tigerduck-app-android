package org.ntust.app.tigerduck.emptyclassroom

import org.ntust.app.tigerduck.shared.Course

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
         * One stop per run of neighbouring periods a course meets in on [weekday] (Mon=1 … Sun=7)
         * in the same room, each period's room resolved as the class table resolves it. A course
         * that moves room between neighbouring periods is two stops, so the walk after it starts
         * from the room it ends in.
         */
        fun stops(courses: List<Course>, weekday: Int): List<ClassStop> = courses.flatMap { course ->
            val periods = course.schedule[weekday].orEmpty()
                .mapNotNull(EmptyClassroomSchedule::periodIndex)
                .distinct()
                .sorted()
            runs(periods) { roomAt(course, weekday, it) }.map { run ->
                val room = roomAt(course, weekday, run.first())
                ClassStop(
                    course = course.displayName,
                    room = room,
                    place = RoomPlace.of(room),
                    firstPeriod = run.first(),
                    lastPeriod = run.last(),
                )
            }
        }.sortedBy { it.firstPeriod }

        /** The first room the class table gives [course] at [period] on [weekday]. */
        private fun roomAt(course: Course, weekday: Int, period: Int): String {
            val shown = course.classroom(weekday, EmptyClassroomSchedule.periodIds[period])
            return Course.splitRooms(shown).firstOrNull() ?: shown
        }

        /** Neighbouring periods with the same [room], each run in order. */
        private fun runs(periods: List<Int>, room: (Int) -> String): List<List<Int>> {
            val runs = mutableListOf<MutableList<Int>>()
            for (period in periods) {
                val last = runs.lastOrNull()
                if (last != null && last.last() + 1 == period && room(last.last()) == room(period)) {
                    last += period
                } else {
                    runs += mutableListOf(period)
                }
            }
            return runs
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
