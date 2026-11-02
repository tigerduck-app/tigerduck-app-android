package org.ntust.app.tigerduck.push

import org.json.JSONObject

// What protocol 2's snapshot, the one /v4 serves, adds to what the app reads of /v3's: the
// TigerSync clock value of the edit behind every value a device sets, the kind of each course
// tombstone, and the resets (tigersync-last-writer-wins D9 to D11). The clock values are whole
// microseconds since the epoch, as the backend sends them.

/** A value as the backend holds it, with the clock value of the edit that set it; null without one. */
data class StampedValue<T>(val value: T, val hlc: Long?)

/** What wrote a course tombstone: a hidden school course, a removed hand-added one, or a reset. */
enum class TombstoneKind(val wire: String) {
    HIDE("hide"),
    REMOVE("remove"),
    RESET("reset"),
    ;

    companion object {
        /** The kind [wire] names; null for none, or for one this build does not know. */
        fun of(wire: String?): TombstoneKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** [this] with [json]'s stamps that belong to no row: the backend's clock, the resets, the holidays. */
internal fun BackendSyncResult.withSnapshotStamps(json: JSONObject): BackendSyncResult = copy(
    serverHlc = nullableLong(json, "server_hlc"),
    coursesResetHlc = nullableLong(json, "courses_reset_hlc"),
    termResets = clockMap(json.optJSONObject("term_resets")),
    holidays = stampedHolidays(json),
)

/** Every row of `holiday_overrides` by holiday id, `notify: false` included; null without the section. */
private fun stampedHolidays(json: JSONObject): Map<Int, StampedValue<Boolean>>? {
    val rows = json.optJSONArray("holiday_overrides") ?: return null
    val holidays = mutableMapOf<Int, StampedValue<Boolean>>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val id = nullableLong(row, "holiday_id")?.toInt() ?: continue
        holidays[id] = StampedValue(row.optBoolean("notify", false), nullableLong(row, "hlc"))
    }
    return holidays
}

/**
 * Keeps [mark] for [key] unless the one kept is stamped later: of two rows of one assignment,
 * the later edit's. On a tie, or with neither stamped, the later row.
 */
internal fun MutableMap<String, StampedValue<String>>.keepLater(key: String, mark: StampedValue<String>) {
    val kept = this[key]
    if (kept == null || (mark.hlc ?: Long.MIN_VALUE) >= (kept.hlc ?: Long.MIN_VALUE)) this[key] = mark
}

/** Every clock value [this] holds, which its parse hears (LWW D4). */
internal fun BackendSyncResult.clockValues(): Sequence<Long> = sequenceOf(
    sequenceOf(serverHlc, coursesResetHlc),
    termResets.orEmpty().values.asSequence(),
    marks.orEmpty().values.asSequence().map { it.hlc },
    holidays.orEmpty().values.asSequence().map { it.hlc },
    serverCourses.asSequence().map { it.addedHlc },
    courseOverrides.asSequence().flatMap { o ->
        sequenceOf(o.colorHexHlc, o.ignoreAssignmentsHlc) + o.customNamesHlc.orEmpty().values.asSequence()
    },
    tombstones.asSequence().map { it.hlc },
).flatten().filterNotNull()

/** [key] of [obj] as a clock value; null when absent, null, or not a number. */
internal fun nullableLong(obj: JSONObject, key: String): Long? =
    if (obj.isNull(key)) null else (obj.opt(key) as? Number)?.toLong()

/** Each key of [obj] with its clock value, leaving out any that is not one; null without [obj]. */
internal fun clockMap(obj: JSONObject?): Map<String, Long>? {
    obj ?: return null
    val values = mutableMapOf<String, Long>()
    for (key in obj.keys()) nullableLong(obj, key)?.let { values[key] = it }
    return values
}
