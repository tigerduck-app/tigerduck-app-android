package org.ntust.app.tigerduck.shared

import org.ntust.app.tigerduck.shared.timeline.CourseChange
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.LocalDate

/**
 * One class of a course on a day the school changed it, as the phone sends it to the watch: a
 * timetable meeting whole, or a piece of one that a change covered only in part. [room], [start]
 * and [end] are set only when the school changed them; the watch takes the rest from the
 * timetable. As the iPhone's `WatchCourse` under `changes`, with its `dayChange`.
 *
 * Read and written by hand in [WearProtocol.Schedule], never by Gson, so a class shaped otherwise
 * by another build is dropped rather than read with nulls in its non-null fields.
 */
data class WatchClassChange(
    /** The class's periods in order; never empty once decoded. */
    val periods: List<String>,
    val cancelled: Boolean = false,
    val room: String? = null,
    /** `HH:mm`, Taipei time. */
    val start: String? = null,
    val end: String? = null,
)

/**
 * The term's changed classes by course number and day, as the phone last sent them; [EMPTY] from
 * a phone or a stored snapshot that predates them, when every class meets as the timetable has it.
 */
data class WatchCourseChanges(val byCourse: Map<String, Map<LocalDate, List<WatchClassChange>>>) {

    /** [courseNo]'s changed classes on [day], cancelled ones included; none on a day left alone. */
    fun on(courseNo: String, day: LocalDate): List<WatchClassChange> = byCourse[courseNo]?.get(day).orEmpty()

    /**
     * What [ClassTimeline][org.ntust.app.tigerduck.shared.timeline.ClassTimeline] needs to lay these
     * classes over [courses]' timetable, as the feed's rows: a cancelled class cancels its periods,
     * and a room, start or end it carries is a room, start or end change over them. The phone sends
     * the classes a meeting splits into, and leaves out a piece that a change made empty, which
     * the timetable would bring back; a period of a meeting that no sent class covers is
     * therefore cancelled here, as the watch has always left it out. A class whose first period is
     * in none of a course's blocks that day is ignored, as before. In memory only: what is sent
     * and what is stored stay as [WearProtocol.Schedule] writes and reads them.
     */
    fun toCourseChanges(courses: List<Course>): CourseChanges {
        if (byCourse.isEmpty()) return CourseChanges.EMPTY
        var id = 0
        val rows = ArrayList<Pair<LocalDate, CourseChange>>()
        for (course in courses) {
            val days = byCourse[course.courseNo] ?: continue
            for ((day, classes) in days) {
                for (block in contiguousPeriodRuns(course.schedule[day.dayOfWeek.value].orEmpty())) {
                    val inBlock = classes.filter { it.periods.firstOrNull() in block }
                    if (inBlock.isEmpty()) continue
                    fun row(kind: CourseChange.Kind, periods: List<String>, room: String? = null, start: String? = null, end: String? = null) {
                        rows += day to CourseChange(++id, course.courseNo, kind, periods, room, start, end, null, null)
                    }
                    for (change in inBlock) {
                        if (change.cancelled) row(CourseChange.Kind.CANCELLED, change.periods)
                        change.room?.let { row(CourseChange.Kind.ROOM, change.periods, room = it) }
                        change.start?.let { row(CourseChange.Kind.START, change.periods, start = it) }
                        change.end?.let { row(CourseChange.Kind.END, change.periods, end = it) }
                    }
                    val left = block.filter { period -> inBlock.none { period in it.periods } }
                    if (left.isNotEmpty()) row(CourseChange.Kind.CANCELLED, left)
                }
            }
        }
        return CourseChanges.of(rows)
    }

    companion object {
        val EMPTY = WatchCourseChanges(emptyMap())
    }
}
