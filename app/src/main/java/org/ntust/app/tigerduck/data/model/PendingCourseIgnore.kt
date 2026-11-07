package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.sync.EditTime

/**
 * A change to whether a course's assignments are ignored that the backend
 * has not confirmed yet, kept in `pending_course_ignores.json` and sent again
 * on the next sync.
 *
 * Every field is nullable or primitive: Gson builds this class without its
 * constructor (see CLAUDE.md), so a field added later reads back from an
 * older file as null or zero rather than crashing.
 */
data class PendingCourseIgnore(
    val semester: String?,
    val courseNo: String?,
    /**
     * The override PATCH's path segment: the course's Moodle idnumber, or
     * "<semester><courseNo>" for a course without one, as the backend keys
     * a course added by hand.
     */
    val moodleId: String?,
    /** The course's numeric Moodle id, when this device knows it. */
    val moodleCourseId: Int?,
    val ignore: Boolean,
    /** When the user made the change; tells it from a later one for the same course. */
    val changedAtMs: Long,
    /** How many times the backend refused it; see CourseIgnoreSync, which parks it after five. */
    val attempts: Int = 0,
    /**
     * The time the change's age is counted from at each send (`age_ms`), epoch ms, which a new
     * change takes from [changedAtMs]. Null on a row written before it was kept (Gson skips the
     * default), which the queue gives its old-entry time as it reads it.
     */
    val madeAtMs: Long? = changedAtMs,
    /** The device clocks at [madeAtMs], which age it while the boot lasts; null on a row an older build wrote. */
    val madeAtClocks: EditClocks? = null,
) {
    /** When the change was made, by the device's clocks; null with no time kept. */
    val madeAt: EditTime? get() = madeAtMs?.let { editTimeOf(it, madeAtClocks) }

    /** What its age counts from: [madeAt], or its floor where later ([EditClocks.floor]). */
    val agedFrom: EditTime? get() = madeAt?.atLeast(madeAtClocks?.floor?.time)

    /** This change counting its age from [floor] at least. */
    fun withFloor(floor: EditTime): PendingCourseIgnore = copy(madeAtClocks = madeAtClocks.withFloor(floor))

    /** Whether [other] is this change, however many times either was refused and whatever its floor. */
    fun isSameChange(other: PendingCourseIgnore): Boolean = asEdit() == other.asEdit()

    private fun asEdit() = copy(attempts = 0, madeAtClocks = madeAtClocks.withoutFloor())

    /** The [CourseIgnores] key, or null for a row too damaged to send. */
    val key: String?
        get() {
            val term = semester?.takeIf { it.isNotBlank() } ?: return null
            val no = courseNo?.takeIf { it.isNotBlank() } ?: return null
            if (moodleId.isNullOrBlank()) return null
            return CourseIgnores.key(term, no)
        }
}
