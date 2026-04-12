package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CourseTimeSlot(
    val id: String,
    val course: Course,
    val start: Date,
    val end: Date,
    val date: Date
) {
    companion object {
        private val dayKeyFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

        fun buildSlots(courses: List<Course>, weekday: Int, on: Date = Date()): List<CourseTimeSlot> {
            val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
            val slots = mutableListOf<CourseTimeSlot>()

            for (course in courses) {
                val periods = course.schedule[weekday]
                if (periods.isNullOrEmpty()) continue

                val sorted = periods.sortedBy { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                val firstTime = AppConstants.PeriodTimes.mapping[sorted.first()] ?: continue
                val lastTime = AppConstants.PeriodTimes.mapping[sorted.last()] ?: continue

                val startDate = dateFromTimeString(firstTime.first, on, cal) ?: continue
                val endDate = dateFromTimeString(lastTime.second, on, cal) ?: continue

                val dayKey = dayKeyFormatter.format(on.toInstant().atZone(AppConstants.TAIPEI_ZONE))
                slots.add(CourseTimeSlot(
                    id = "${course.courseNo}_$dayKey",
                    course = course,
                    start = startDate,
                    end = endDate,
                    date = on
                ))
            }
            return slots.sortedBy { it.start }
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

sealed class CourseState {
    data class InClass(val slot: CourseTimeSlot) : CourseState()
    data class Between(val previous: CourseTimeSlot?, val next: CourseTimeSlot?) : CourseState()
    data class BeforeFirst(val next: CourseTimeSlot) : CourseState()
    data class AfterLast(val previous: CourseTimeSlot) : CourseState()
}
