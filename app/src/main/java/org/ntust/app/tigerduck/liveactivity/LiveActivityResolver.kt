package org.ntust.app.tigerduck.liveactivity

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.collapseContiguousPeriods
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.Course
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date

/**
 * Picks the single scenario that should drive the Live Update right now, or
 * null if nothing qualifies under current preferences.
 *
 * Priority mirrors the iOS spec:
 * 1. inClass         — current course for today
 * 2. classPreparing  — next course within `classPreparingLeadTime`
 * 3. assignmentUrgent — earliest uncompleted assignment due within
 *    `assignmentLeadTime`
 */
class LiveActivityResolver {

    fun resolve(
        courses: List<Course>,
        assignments: List<Assignment>,
        skippedDates: Map<String, List<String>>,
        preferences: LiveActivityPreferences,
        accentHex: Int,
        now: Date = Date(),
    ): LiveActivitySnapshot? {
        if (!preferences.isEnabled) return null

        val todaySlots = buildTodaySlots(courses, now)
            .filter { !it.isSkipped(skippedDates) }

        if (preferences.showInClass) {
            val current = todaySlots.firstOrNull { now in it.start..it.end }
            if (current != null) {
                return inClassSnapshot(current, now, accentHex)
            }
        }

        if (preferences.showClassPreparing) {
            val upcoming = todaySlots
                .filter { it.start.after(now) }
                .minByOrNull { it.start }
            if (upcoming != null) {
                val secUntil = (upcoming.start.time - now.time) / 1000
                if (secUntil <= preferences.classPreparingLeadTimeSec) {
                    return classPreparingSnapshot(upcoming, accentHex)
                }
            }
        }

        if (preferences.showAssignment) {
            val urgent = assignments
                .asSequence()
                .filter { !it.isCompleted && it.dueDate.after(now) }
                .filter { (it.dueDate.time - now.time) / 1000 <= preferences.assignmentLeadTimeSec }
                .minByOrNull { it.dueDate }
            if (urgent != null) return assignmentSnapshot(urgent, accentHex)
        }

        return null
    }

    data class Slot(
        val course: Course,
        val periods: List<String>,
        val start: Date,
        val end: Date,
    ) {
        fun isSkipped(skippedDates: Map<String, List<String>>): Boolean {
            val key = start.toInstant()
                .atZone(AppConstants.TAIPEI_ZONE)
                .toLocalDate()
                .toString()
            return skippedDates[course.courseNo]?.contains(key) == true
        }
    }

    private fun buildTodaySlots(courses: List<Course>, now: Date): List<Slot> {
        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ).apply { time = now }
        val weekdayIdx = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
        val today = LocalDate.now(AppConstants.TAIPEI_ZONE)

        val results = mutableListOf<Slot>()
        for (course in courses) {
            val periods = course.schedule[weekdayIdx] ?: continue
            if (periods.isEmpty()) continue
            val ranges = collapseContiguousPeriods(periods)
            for ((first, last) in ranges) {
                val startTime = periodStart(first) ?: continue
                val endTime = periodEnd(last) ?: continue
                val startZdt = ZonedDateTime.of(today, startTime, AppConstants.TAIPEI_ZONE)
                val endZdt = ZonedDateTime.of(today, endTime, AppConstants.TAIPEI_ZONE)
                results += Slot(
                    course = course,
                    periods = periods,
                    start = Date.from(startZdt.toInstant()),
                    end = Date.from(endZdt.toInstant()),
                )
            }
        }
        return results.sortedBy { it.start }
    }

    private fun inClassSnapshot(slot: Slot, now: Date, accentHex: Int): LiveActivitySnapshot {
        val total = (slot.end.time - slot.start.time).coerceAtLeast(1L)
        val elapsed = (now.time - slot.start.time).coerceAtLeast(0L)
        val progress = (elapsed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        return LiveActivitySnapshot(
            scenario = LiveActivityScenario.IN_CLASS,
            title = slot.course.courseName,
            subtitle = formatTimeRange(slot.start, slot.end),
            locationText = slot.course.classroom.ifBlank { null },
            instructor = slot.course.instructor.ifBlank { null },
            countdownTarget = slot.end,
            progress = progress,
            accentHex = accentHex,
            sourceId = "class-${slot.course.courseNo}-${slot.periods.firstOrNull()}",
        )
    }

    private fun classPreparingSnapshot(slot: Slot, accentHex: Int): LiveActivitySnapshot =
        LiveActivitySnapshot(
            scenario = LiveActivityScenario.CLASS_PREPARING,
            title = slot.course.courseName,
            subtitle = formatTimeRange(slot.start, slot.end),
            locationText = slot.course.classroom.ifBlank { null },
            instructor = slot.course.instructor.ifBlank { null },
            countdownTarget = slot.start,
            progress = null,
            accentHex = accentHex,
            sourceId = "prep-${slot.course.courseNo}-${slot.periods.firstOrNull()}",
        )

    private fun assignmentSnapshot(a: Assignment, accentHex: Int): LiveActivitySnapshot =
        LiveActivitySnapshot(
            scenario = LiveActivityScenario.ASSIGNMENT_URGENT,
            title = a.title,
            subtitle = a.courseName,
            locationText = null,
            instructor = null,
            countdownTarget = a.dueDate,
            progress = null,
            accentHex = accentHex,
            sourceId = "assignment-${a.assignmentId}",
        )

    private fun formatTimeRange(start: Date, end: Date): String {
        val startTime = start.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalTime()
        val endTime = end.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalTime()
        return "%02d:%02d–%02d:%02d".format(
            startTime.hour, startTime.minute, endTime.hour, endTime.minute,
        )
    }

    companion object {
        private fun parseTime(text: String): LocalTime? = runCatching {
            val (h, m) = text.split(":").map { it.toInt() }
            LocalTime.of(h, m)
        }.getOrNull()

        private fun periodStart(id: String): LocalTime? =
            AppConstants.PeriodTimes.mapping[id]?.first?.let(::parseTime)

        private fun periodEnd(id: String): LocalTime? =
            AppConstants.PeriodTimes.mapping[id]?.second?.let(::parseTime)

    }
}
