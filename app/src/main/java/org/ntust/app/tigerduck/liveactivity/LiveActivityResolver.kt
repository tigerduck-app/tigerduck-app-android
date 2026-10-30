package org.ntust.app.tigerduck.liveactivity

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
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
 *
 * Today's classes are the timetable's as the school's course changes leave
 * them ([CourseMeetings.meetingSlots]), as iOS's `CourseTimelineResolver`
 * builds its timeline from them: a cancelled class is passed over like a
 * skipped one, and the rest run at their changed times in their changed
 * rooms.
 */
class LiveActivityResolver {

    fun resolve(
        courses: List<Course>,
        assignments: List<Assignment>,
        skippedDates: Map<String, List<String>>,
        preferences: LiveActivityPreferences,
        accentHex: Int,
        now: Date = Date(),
        changes: CourseChanges = CourseChanges.EMPTY,
    ): LiveActivitySnapshot? {
        if (!preferences.isEnabled) return null

        val todaySlots = classesToday(courses, now, skippedDates, changes)
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
     * Skipped and cancelled slots are excluded (mirroring [resolve]) so no
     * alarm is armed for a class that does not meet — the wakeup would
     * resolve to nothing and just burn battery.
     */
    fun todaySlotsAfter(
        courses: List<Course>,
        now: Date,
        skippedDates: Map<String, List<String>> = emptyMap(),
        changes: CourseChanges = CourseChanges.EMPTY,
    ): List<Slot> =
        classesToday(courses, now, skippedDates, changes)
            .filter { it.end.after(now) }

    /** One class today: a timetable block, or the piece of one a course change split off. */
    class Slot internal constructor(private val slot: ClassSlot) {
        val course: Course get() = slot.course

        /** This class's periods, not every period of the course that day. */
        val periods: List<String> get() = slot.periods

        /** Its changed start and end, when the school moved them. */
        val start: Date get() = Date.from(slot.startsAt)
        val end: Date get() = Date.from(slot.endsAt)

        /**
         * Its room, the changed one included: a course meeting twice a day
         * can switch rooms in between.
         */
        val classroom: String get() = slot.room
    }

    /**
     * Today's classes that meet, by start: none the user skipped or the
     * school cancelled. Each piece of a meeting ends at its own end, as iOS
     * ends a class's activity ([CourseMeetings.hasEnded], #905); one a change
     * dropped is not built at all, so the meeting's other pieces carry on
     * (#904).
     */
    private fun classesToday(
        courses: List<Course>,
        now: Date,
        skippedDates: Map<String, List<String>>,
        changes: CourseChanges,
    ): List<Slot> {
        val today = now.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate()
        return CourseMeetings.meetingSlots(
            courses, today, changes,
            // LiveActivityManager hands over no courses on a day classes do not meet.
            isClassDay = { true },
            skipped = { course, day -> skippedDates[course.courseNo]?.contains(day.toString()) == true },
        ).map(::Slot)
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
}
