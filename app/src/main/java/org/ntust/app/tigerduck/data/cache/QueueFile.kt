package org.ntust.app.tigerduck.data.cache

/**
 * The files the outbox queues are kept in, under the user data folder. The names are persisted:
 * an older build wrote them and the next one must read them, so they never change.
 */
enum class QueueFile(val fileName: String) {
    COURSE_IGNORES("pending_course_ignores.json"),
    UNSENT_MARKS("unsent_marks.json"),
    COURSE_ADDS("pending_course_adds.json"),
    COURSE_ADD_TIMES("pending_course_add_times.json"),
    COURSE_DELETES("pending_course_deletes.json"),
    COURSE_OVERRIDES("pending_course_overrides.json"),
}
