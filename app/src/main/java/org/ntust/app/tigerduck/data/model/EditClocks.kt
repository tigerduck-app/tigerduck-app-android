package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.sync.EditTime

/**
 * The device clocks a queued edit was made at, beside its wall time, which its queue keeps as it
 * always has: `SystemClock.elapsedRealtime()` ([elapsedMs]) and `Settings.Global.BOOT_COUNT`
 * ([boot]), so its age (`age_ms`) is the elapsed clock's while the boot lasts. Kept in the queues'
 * files (`unsent_marks.json`, `pending_course_overrides.json`, `pending_course_ignores.json`, and
 * the course adds' and deletes' clocks files by moment).
 *
 * Every field is nullable: Gson builds this class without its constructor (see CLAUDE.md), and an
 * edit an older build queued has none of them, which ages it by the wall clock.
 */
data class EditClocks(
    val elapsedMs: Long? = null,
    val boot: Int? = null,
) {
    companion object {
        /** [time]'s clocks, or null when it kept none (an older build's time, the oldest, an old-entry time). */
        fun of(time: EditTime): EditClocks? =
            if (time.elapsedMs == null && time.boot == null) null else EditClocks(time.elapsedMs, time.boot)
    }
}

/** The time of an edit made at [wallMs] with [clocks], null for none kept. */
fun editTimeOf(wallMs: Long, clocks: EditClocks?): EditTime = EditTime(wallMs, clocks?.elapsedMs, clocks?.boot)
