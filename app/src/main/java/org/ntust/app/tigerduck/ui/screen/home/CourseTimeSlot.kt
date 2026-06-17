package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.periodOrder
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    val date: Date
) {
    companion object {
        private val dayKeyFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

        fun buildSlots(
            courses: List<Course>,
            weekday: Int,
            on: Date = Date()
        ): List<CourseTimeSlot> {
            val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
            val slots = mutableListOf<CourseTimeSlot>()
            val dayKey = dayKeyFormatter.format(on.toInstant().atZone(AppConstants.TAIPEI_ZONE))

            for (course in courses) {
                val periods = course.schedule[weekday]
                if (periods.isNullOrEmpty()) continue

                for (run in groupContiguous(periods)) {
                    val firstTime = AppConstants.PeriodTimes.mapping[run.first()] ?: continue
                    val lastTime = AppConstants.PeriodTimes.mapping[run.last()] ?: continue

                    val startDate = dateFromTimeString(firstTime.first, on, cal) ?: continue
                    val endDate = dateFromTimeString(lastTime.second, on, cal) ?: continue

                    slots.add(
                        CourseTimeSlot(
                            id = "${course.courseNo}_${dayKey}_${run.first()}",
                            course = course,
                            periods = run,
                            start = startDate,
                            end = endDate,
                            date = on
                        )
                    )
                }
            }
            return slots.sortedBy { it.start }
        }

        private fun groupContiguous(periods: List<String>): List<List<String>> {
            val sorted = periods.sortedBy(::periodOrder)
            if (sorted.isEmpty()) return emptyList()
            val runs = mutableListOf<MutableList<String>>()
            for (p in sorted) {
                val curOrder = periodOrder(p)
                val last = runs.lastOrNull()
                val lastOrder = last?.lastOrNull()?.let(::periodOrder)
                if (last != null && lastOrder != null && lastOrder >= 0 &&
                    curOrder == lastOrder + 1
                ) {
                    last.add(p)
                } else {
                    runs.add(mutableListOf(p))
                }
            }
            return runs
        }

        fun buildMultiDaySlots(
            courses: List<Course>,
            centerDate: Date,
            dayRadius: Int = 28
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

                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val weekday = when (dayOfWeek) {
                    Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
                    Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
                    Calendar.SUNDAY -> 7; else -> 1
                }

                allSlots.addAll(buildSlots(courses, weekday, date))
            }
            return allSlots.sortedBy { it.start }
        }

        private fun dateFromTimeString(time: String, on: Date, cal: Calendar): Date? {
            val parts = time.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size != 2) return null
            cal.time = on
            cal.set(Calendar.HOUR_OF_DAY, parts[0])
            cal.set(Calendar.MINUTE, parts[1])
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.time
        }
    }
}

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
 * Classroom resolved per-period for this slot — iterates [periods] through
 * [Course.classroom] (weekday + period) so a same-day course with split
 * rooms (morning lecture in A, afternoon lab in B) shows only the room(s)
 * that match *this* slot's periods, not the union across the whole day.
 */
val CourseTimeSlot.classroom: String
    get() {
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
