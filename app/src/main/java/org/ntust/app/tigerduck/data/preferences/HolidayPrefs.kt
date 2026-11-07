package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import org.ntust.app.tigerduck.sync.EditTime

// The holiday choices' preferences: sets of holiday ids, and the time each choice was made.

internal fun SharedPreferences.getIds(key: String): Set<Int> =
    getStringSet(key, emptySet())?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

internal fun SharedPreferences.Editor.putIds(key: String, ids: Set<Int>): SharedPreferences.Editor =
    putStringSet(key, ids.map(Int::toString).toSet())

/**
 * When a holiday's choice was made ([madeAt]), and its floor, the end of the latest write of the
 * holiday that went out at or before then ([floor], the per-key rule): its age counts from the later.
 */
data class HolidayEditTime(val madeAt: EditTime, val floor: EditTime? = null) {
    /** What the choice's age counts from. */
    val agedFrom: EditTime get() = madeAt.atLeast(floor)
}

/**
 * "id:epochMs:elapsedMs:boot" entries, the device clocks at the choice ([EditTime]) after its wall
 * time, either empty when unknown, then its floor's three the same way where it has one; an older
 * build's "id:epochMs" reads with neither. One whose id or wall time does not read is left out, as
 * a time not kept, and a floor whose wall time does not read as none.
 */
internal fun SharedPreferences.getTimesById(key: String): Map<Int, HolidayEditTime> =
    getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split(':')
        val id = parts[0].toIntOrNull() ?: return@mapNotNull null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        val floor = parts.getOrNull(4)?.toLongOrNull()?.let { EditTime(it, parts.getOrNull(5)?.toLongOrNull(), parts.getOrNull(6)?.toIntOrNull()) }
        id to HolidayEditTime(EditTime(at, parts.getOrNull(2)?.toLongOrNull(), parts.getOrNull(3)?.toIntOrNull()), floor)
    }.toMap()

internal fun SharedPreferences.Editor.putTimesById(key: String, times: Map<Int, HolidayEditTime>): SharedPreferences.Editor =
    putStringSet(key, times.map { (id, time) -> "$id:${parts(time.madeAt)}" + (time.floor?.let { ":${parts(it)}" } ?: "") }.toSet())

// [at] as "epochMs:elapsedMs:boot", either clock empty when unknown.
private fun parts(at: EditTime): String = "${at.wallMs}:${at.elapsedMs ?: ""}:${at.boot ?: ""}"
