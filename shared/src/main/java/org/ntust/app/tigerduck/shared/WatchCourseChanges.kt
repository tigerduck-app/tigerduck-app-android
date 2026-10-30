package org.ntust.app.tigerduck.shared

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

    companion object {
        val EMPTY = WatchCourseChanges(emptyMap())
    }
}
