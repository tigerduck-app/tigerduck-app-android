package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.sync.EditTime

/**
 * The device clocks a queued edit was made at, beside its wall time, which its queue keeps as it
 * always has: `SystemClock.elapsedRealtime()` ([elapsedMs]) and `Settings.Global.BOOT_COUNT`
 * ([boot]), so its age (`age_ms`) is the elapsed clock's while the boot lasts. Kept in the queues'
 * files (`unsent_marks.json`, `pending_course_overrides.json`, `pending_course_ignores.json`, and
 * the course adds' and deletes' clocks files by moment). [wallMs] is the edit's wall time where its
 * queue keeps another number in its place: a course list's moment, which runs ahead of the wall
 * clock once it was set back ([org.ntust.app.tigerduck.data.CourseMoment]); null where the two agree.
 *
 * Every field is nullable: Gson builds this class without its constructor (see CLAUDE.md), and an
 * edit an older build queued has none of them, which ages it by the wall clock.
 */
data class EditClocks(
    val elapsedMs: Long? = null,
    val boot: Int? = null,
    val wallMs: Long? = null,
) {
    companion object {
        /**
         * [time]'s clocks, kept by [moment], or null when it kept none and its wall time is the moment
         * (an older build's time, the oldest, an old-entry time).
         */
        fun of(time: EditTime, moment: Long = time.wallMs): EditClocks? {
            val wall = time.wallMs.takeIf { it != moment }
            return if (time.elapsedMs == null && time.boot == null && wall == null) null else EditClocks(time.elapsedMs, time.boot, wall)
        }
    }
}

/** The time of an edit kept at [moment] with [clocks], null for none kept: their wall time where they keep one, else the moment. */
fun editTimeOf(moment: Long, clocks: EditClocks?): EditTime = EditTime(clocks?.wallMs ?: moment, clocks?.elapsedMs, clocks?.boot)
