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
 * [floor] is the end of the latest write of the edit's key that went out at or before it was made,
 * its age counted from there at least (the per-key rule, as the iPhone's, tigerduck-app#1008),
 * written as that write is answered and kept through the edit's tries, parked or not.
 *
 * Every field is nullable: Gson builds this class without its constructor (see CLAUDE.md), and an
 * edit an older build queued has none of them, which ages it by the wall clock.
 */
data class EditClocks(
    val elapsedMs: Long? = null,
    val boot: Int? = null,
    val wallMs: Long? = null,
    val floor: EditFloor? = null,
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

/** [this] clocks with the floor [floor] ([EditClocks.floor]), clocks of none kept but the floor. */
fun EditClocks?.withFloor(floor: EditTime): EditClocks = (this ?: EditClocks()).copy(floor = EditFloor.of(floor))

/** [this] clocks with no floor, for telling one edit from another: a floor raised is still the same edit. */
fun EditClocks?.withoutFloor(): EditClocks? = this?.copy(floor = null)?.takeUnless { it == EditClocks() }

/**
 * A floor ([EditClocks.floor]): the moment a write of the edit's key was answered, by the device's
 * clocks as an [EditTime] keeps them. Every field nullable, as Gson builds it; one with no wall time
 * reads as none ([time]).
 */
data class EditFloor(
    val wallMs: Long? = null,
    val elapsedMs: Long? = null,
    val boot: Int? = null,
) {
    /** The floor as a time, or null for one that does not read. */
    val time: EditTime? get() = wallMs?.let { EditTime(it, elapsedMs, boot) }

    companion object {
        fun of(time: EditTime): EditFloor = EditFloor(time.wallMs, time.elapsedMs, time.boot)
    }
}
