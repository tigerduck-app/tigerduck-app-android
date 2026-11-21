package org.ntust.app.tigerduck.wear

import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import org.ntust.app.tigerduck.shared.timeline.ClassSlot
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The classes the school changed in [courses], on every day of the feed, past days included, in
 * the form the watch reads under [WearProtocol.Schedule.KEY_COURSE_CHANGES]: each meeting moved,
 * cancelled, given a room or held online ([online] its room), as the classes it splits into,
 * cancelled ones included. A meeting with only a note or an assessment, which the watch does not
 * mark, is left out. As the iPhone's `WatchPayloadEncoder` fills `changes`.
 */
internal fun wearCourseChanges(courses: List<Course>, changes: CourseChanges, online: String): String {
    val byCourse = courses.associate { course ->
        course.courseNo to changes.days(course.courseNo)
            .associateWith { day -> changedTimetable(course, day, changes).map { wearClassChange(it, online) } }
            .filterValues { it.isNotEmpty() }
    }
    return WearProtocol.Schedule.encodeCourseChanges(WatchCourseChanges(byCourse.filterValues { it.isNotEmpty() }))
}

/**
 * [course]'s changed classes on [day] of each meeting whose classes change the timetable: a
 * meeting's classes go together, as the watch reads a period no class covers as cancelled.
 */
private fun changedTimetable(course: Course, day: LocalDate, changes: CourseChanges): List<ClassSlot> =
    CourseMeetings.changedClasses(course, day, changes)
        .groupBy { slot -> contiguousPeriodRuns(course.schedule[day.dayOfWeek.value].orEmpty()).indexOfFirst { slot.periods.first() in it } }
        .values.filter { meeting -> meeting.any { it.isCancelled || it.hasChangedRoom || it.hasChangedTime } }
        .flatten()

/** [slot] with only the values the school changed, as the iPhone's `SnapshotCourseChange`. */
private fun wearClassChange(slot: ClassSlot, online: String) = WatchClassChange(
    periods = slot.periods,
    cancelled = slot.isCancelled,
    room = slot.room(online).takeIf { slot.hasChangedRoom },
    start = slot.originalStart?.let { wearHm(slot.start) },
    end = slot.originalEnd?.let { wearHm(slot.end) },
)

// Latin digits in every locale, as the watch parses them.
private fun wearHm(time: LocalTime): String = "%02d:%02d".format(Locale.US, time.hour, time.minute)
