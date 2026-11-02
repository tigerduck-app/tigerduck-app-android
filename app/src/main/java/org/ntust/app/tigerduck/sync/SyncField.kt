package org.ntust.app.tigerduck.sync

/**
 * One synced field, named the way the full sync's snapshot lays it out: its list, the row's key and
 * the field (`course_overrides/client:1141:CS1001/color_hex`). An outbox entry and a snapshot's row
 * of the same field name it alike, so a pull can ask, field by field, whether this phone holds an
 * edit of it or an acknowledgment newer than the snapshot (LWW D12).
 */
object SyncField {
    /** The key the backend knows a course by in a term, as both apps build it (`docs/api-v4.md`). */
    fun courseKey(semester: String, courseNo: String): String = "client:$semester:$courseNo"

    /** An assignment's mark (`local_status`), by its Moodle id. */
    fun mark(assignmentId: String): String = "assignment_overrides/$assignmentId/local_status"

    /** A course's custom color. */
    fun color(courseKey: String): String = "course_overrides/$courseKey/color_hex"

    /** A course's custom name in [locale]. */
    fun name(courseKey: String, locale: String): String = "course_overrides/$courseKey/custom_names/$locale"

    /** Whether a course's assignments are ignored. */
    fun ignore(courseKey: String): String = "course_overrides/$courseKey/ignore_assignments"

    /** Whether a holiday notifies. */
    fun holiday(id: Int): String = "holiday_overrides/$id/notify"

    /** Whether a course is on the class table: an add, a hide or a removal of it. */
    fun membership(courseKey: String): String = "courses/$courseKey"
}
