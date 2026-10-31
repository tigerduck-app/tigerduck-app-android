package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.util.Calendar
import java.util.Date

data class CourseTimeSlot(
    val id: String,
    val course: Course,
    /**
     * Period IDs covered by this slot, chronologically. A same-day course
     * with a noon break (e.g. periods [3,4,6,7]) yields *two* slots so each
     * carries its own time range and resolves only its own room — sharing
     * one slot would stretch InClass across lunch and merge both rooms.
     */
    val periods: List<String>,
    val start: Date,
    val end: Date,
    val date: Date,
    /** Whether the school cancelled this class. A cancelled class stays on the slider, struck through. */
    val isCancelled: Boolean = false,
    /** The class as the school changed it, for the course detail; null for one built without the feed. */
    val classSlot: org.ntust.app.tigerduck.coursechanges.ClassSlot? = null,
    /** The room the school moved this class to; null keeps the timetable's. */
    val movedRoom: String? = null,
    /** Whether the school moved this class's start or end off the timetable's. */
    val hasChangedTime: Boolean = false,
) {
    val hasChangedRoom: Boolean get() = movedRoom != null

    companion object {
        /**
         * The classes of [courses] on [on]'s day, as the school's [changes] split, move and cancel
         * them: a meeting cancelled or moved in part is a slot per piece, as iOS
         * `CourseTimeSlot.buildSlots`. The day is [on]'s; [weekday] is its weekday.
         */
        fun buildSlots(
            courses: List<Course>,
            @Suppress("UNUSED_PARAMETER") weekday: Int,
            on: Date = Date(),
            changes: CourseChanges = CourseChanges.EMPTY,
        ): List<CourseTimeSlot> {
            val day = on.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate()
            return CourseMeetings.buildSlots(courses, day, changes).map { slot ->
                CourseTimeSlot(
                    id = slot.id,
                    course = slot.course,
                    periods = slot.periods,
                    start = Date.from(slot.startsAt),
                    end = Date.from(slot.endsAt),
                    date = on,
                    isCancelled = slot.isCancelled,
                    movedRoom = slot.room.takeIf { slot.hasChangedRoom },
                    hasChangedTime = slot.hasChangedTime,
                    classSlot = slot,
                )
            }
        }

        fun buildMultiDaySlots(
            courses: List<Course>,
            centerDate: Date,
            dayRadius: Int = 28,
            changes: CourseChanges = CourseChanges.EMPTY,
        ): List<CourseTimeSlot> {
            val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
            val allSlots = mutableListOf<CourseTimeSlot>()

            for (offset in -dayRadius..dayRadius) {
                cal.time = centerDate
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_YEAR, offset)
                val date = cal.time

                allSlots.addAll(buildSlots(courses, AppConstants.weekdayIndex(cal.get(Calendar.DAY_OF_WEEK)), date, changes))
            }
            return allSlots.sortedBy { it.start }
        }
    }
}

/** This slot's day, in Taipei tz. */
val CourseTimeSlot.localDate: LocalDate
    get() = date.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate()

/** Weekday (1=Mon..7=Sun) for this slot's date, in Taipei tz. */
val CourseTimeSlot.weekday: Int
    get() {
        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
        cal.time = date
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7; else -> 1
        }
    }

/**
 * What the class table's course detail opens on: the course, the weekday and the period it was
 * tapped at, and the day whose course changes it shows, null for none.
 */
data class CourseDetailSelection(
    val course: Course,
    val weekday: Int,
    val periodId: String,
    val date: java.time.LocalDate? = null,
    /** The piece of the meeting the card stands for, whose changes the detail shows. */
    val slot: org.ntust.app.tigerduck.coursechanges.ClassSlot? = null,
)

/** This slot's course on its own day and first period, so the detail shows this slot's room, time and changes. */
val CourseTimeSlot.detailSelection: CourseDetailSelection
    get() = CourseDetailSelection(course, weekday, periods.firstOrNull().orEmpty(), localDate, classSlot)

/**
 * The room the school moved this slot to, else the classroom resolved per-period for this slot — iterates [periods] through
 * [Course.classroom] (weekday + period) so a same-day course with split
 * rooms (morning lecture in A, afternoon lab in B) shows only the room(s)
 * that match *this* slot's periods, not the union across the whole day.
 */
val CourseTimeSlot.classroom: String
    get() {
        movedRoom?.let { return it }
        val seen = LinkedHashSet<String>()
        for (period in periods) {
            for (part in Course.splitRooms(course.classroom(weekday, period))) seen.add(part)
        }
        return if (seen.isEmpty()) course.classroom(weekday) else seen.joinToString(", ")
    }

sealed class CourseState {
    /**
     * One or more slots whose [start, end] currently contains the selected
     * time. 衝堂 (overlapping concurrent classes) produces a list with >1
     * entry; each must remain individually selectable so callers can open
     * the right slot's per-period room and assignments.
     */
    data class InClass(val slots: List<CourseTimeSlot>) : CourseState()
    data class Between(val previous: CourseTimeSlot?, val next: CourseTimeSlot?) : CourseState()
    data class BeforeFirst(val next: CourseTimeSlot) : CourseState()
    data class AfterLast(val previous: CourseTimeSlot) : CourseState()
}
