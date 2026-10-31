package org.ntust.app.tigerduck.shared.timeline

import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.time.Instant
import java.time.LocalDate

/**
 * What is going on and what comes next, from the timetable under the school's changes: the one
 * home of the rule every screen's "now" and "next" follows. A class is on from its start minute
 * and over from its end minute (`start <= now < end`, so one ending at 09:00 is over at 09:00:00),
 * the end being the changed one when the school moved it. A cancelled class is neither on nor
 * next. The day is Taipei's, whatever zone the phone is in. Callers pass "now" in, read from
 * [org.ntust.app.tigerduck.shared.clock.AppClock] so the debug override moves them all together.
 */
object ClassTimeline {

    /** Every class of [courses] on [date], by start; a cancelled one is in the list and marked. */
    fun meetings(courses: List<Course>, date: LocalDate, changes: CourseChanges): List<ClassSlot> =
        CourseMeetings.buildSlots(courses, date, changes)

    /** The classes running at [now], by start. */
    fun ongoing(courses: List<Course>, now: Instant, changes: CourseChanges): List<ClassSlot> =
        meetings(courses, now.atZone(TaipeiClock.zone).toLocalDate(), changes)
            .filter { !it.isCancelled && it.startsAt <= now && now < it.endsAt }

    /**
     * The first class that starts after [now]: today's, or else the earliest of the next days, up
     * to [daysAhead] days past today (0 reads today alone, 7 reaches the same weekday next week).
     * A class starting at [now] is on, not next.
     */
    fun next(courses: List<Course>, now: Instant, changes: CourseChanges, daysAhead: Int): ClassSlot? {
        val today = now.atZone(TaipeiClock.zone).toLocalDate()
        for (offset in 0L..daysAhead.toLong()) {
            val first = meetings(courses, today.plusDays(offset), changes)
                .firstOrNull { !it.isCancelled && it.startsAt > now }
            if (first != null) return first
        }
        return null
    }

    /** The moments the day's picture changes: every start and end of a class not cancelled, sorted, each once. */
    fun boundaries(courses: List<Course>, date: LocalDate, changes: CourseChanges): List<Instant> =
        meetings(courses, date, changes)
            .filter { !it.isCancelled }
            .flatMap { listOf(it.startsAt, it.endsAt) }
            .distinct()
            .sorted()
}
