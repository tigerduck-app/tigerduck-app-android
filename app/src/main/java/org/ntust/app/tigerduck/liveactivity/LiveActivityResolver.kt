package org.ntust.app.tigerduck.liveactivity

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date

/**
 * Picks the single scenario that should drive the Live Update right now, or
 * null if nothing qualifies under current preferences.
 *
 * Priority mirrors iOS's `LiveActivityScenarioResolver`. The assignment
 * comes first, a product decision, so a due-soon warning still shows during
 * class; turning the assignment scenario off gives class-first behaviour.
 * 1. assignmentUrgent — earliest uncompleted assignment due within
 *    `assignmentLeadTime`. Ignored and marked-done ones are the caller's to
 *    leave out; see [org.ntust.app.tigerduck.data.OutstandingAssignments].
 * 2. inClass         — current course for today
 * 3. classPreparing  — next course within `classPreparingLeadTime`
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
        // Whether or not the in-class scenario is on: a deadline shown now
        // says whether a class is going on under it.
        val current = todaySlots.firstOrNull { now in it.start..it.end }

        if (preferences.showAssignment) {
            val urgent = assignments
                .asSequence()
                .filter { !it.isCompleted && it.studentDueDate.after(now) }
                .filter { (it.studentDueDate.time - now.time) / 1000 <= preferences.assignmentLeadTimeSec }
                .minByOrNull { it.studentDueDate }
            if (urgent != null) return assignmentSnapshot(urgent, accentHex, duringClass = current != null)
        }

        if (preferences.showInClass && current != null) {
            return inClassSnapshot(current, now, accentHex)
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

        return null
    }

    /**
     * Today's class slots whose end is still after [now], sorted by start.
     * Used by the boundary scheduler so it can wake the manager at the
     * preparing-window crossing / class-start / class-end of the next slot.
     * Skipped slots are excluded (mirroring [resolve]) so no alarm is armed
     * for a class the user marked as skipped — the wakeup would resolve to
     * nothing and just burn battery.
     */
    fun todaySlotsAfter(
        courses: List<Course>,
        now: Date,
        skippedDates: Map<String, List<String>> = emptyMap(),
    ): List<Slot> =
        buildTodaySlots(courses, now)
            .filter { !it.isSkipped(skippedDates) }
            .filter { it.end.after(now) }

    data class Slot(
        val course: Course,
        /** This block's periods, not every period of the course that day. */
        val periods: List<String>,
        val start: Date,
        val end: Date,
        val weekday: Int,
    ) {
        fun isSkipped(skippedDates: Map<String, List<String>>): Boolean {
            val key = start.toInstant()
                .atZone(AppConstants.TAIPEI_ZONE)
                .toLocalDate()
                .toString()
            return skippedDates[course.courseNo]?.contains(key) == true
        }

        /** This block's room: a course meeting twice a day can switch rooms in between. */
        val classroom: String get() = course.blockClassroom(weekday, periods)
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
        val today = now.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate()

        val results = mutableListOf<Slot>()
        for (course in courses) {
            val periods = course.schedule[weekdayIdx] ?: continue
            if (periods.isEmpty()) continue
            for (block in contiguousPeriodRuns(periods)) {
                val startTime = periodStart(block.first()) ?: continue
                val endTime = periodEnd(block.last()) ?: continue
                val startZdt = ZonedDateTime.of(today, startTime, AppConstants.TAIPEI_ZONE)
                val endZdt = ZonedDateTime.of(today, endTime, AppConstants.TAIPEI_ZONE)
                results += Slot(
                    course = course,
                    periods = block,
                    start = Date.from(startZdt.toInstant()),
                    end = Date.from(endZdt.toInstant()),
                    weekday = weekdayIdx,
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
            title = slot.course.displayName,
            subtitle = formatTimeRange(slot.start, slot.end),
            locationText = slot.classroom.ifBlank { null },
            instructor = slot.course.instructor.ifBlank { null },
            countdownTarget = slot.end,
            progress = progress,
            accentHex = accentHex,
            sourceId = "class-${slot.course.courseNo}-${slot.periods.firstOrNull()}",
            link = AppLink.ClassTable,
            duringClass = true,
        )
    }

    private fun classPreparingSnapshot(slot: Slot, accentHex: Int): LiveActivitySnapshot =
        LiveActivitySnapshot(
            scenario = LiveActivityScenario.CLASS_PREPARING,
            title = slot.course.displayName,
            subtitle = formatTimeRange(slot.start, slot.end),
            locationText = slot.classroom.ifBlank { null },
            instructor = slot.course.instructor.ifBlank { null },
            countdownTarget = slot.start,
            progress = null,
            accentHex = accentHex,
            sourceId = "prep-${slot.course.courseNo}-${slot.periods.firstOrNull()}",
            link = AppLink.ClassTable,
        )

    private fun assignmentSnapshot(a: Assignment, accentHex: Int, duringClass: Boolean): LiveActivitySnapshot =
        LiveActivitySnapshot(
            scenario = LiveActivityScenario.ASSIGNMENT_URGENT,
            title = a.title,
            subtitle = a.courseName,
            locationText = null,
            instructor = null,
            countdownTarget = a.studentDueDate,
            progress = null,
            accentHex = accentHex,
            sourceId = "assignment-${a.assignmentId}",
            link = AppLink.assignment(a.assignmentId),
            duringClass = duringClass,
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
