package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import org.ntust.app.tigerduck.sync.EditTime

// The holiday choices' preferences: sets of holiday ids, and the time each choice was made.

internal fun SharedPreferences.getIds(key: String): Set<Int> =
    getStringSet(key, emptySet())?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

internal fun SharedPreferences.Editor.putIds(key: String, ids: Set<Int>): SharedPreferences.Editor =
    putStringSet(key, ids.map(Int::toString).toSet())

/**
 * "id:epochMs:elapsedMs:boot" entries, the device clocks at the choice ([EditTime]) after its wall
 * time, either empty when unknown; an older build's "id:epochMs" reads with neither. One whose id
 * or wall time does not read is left out, as a time not kept.
 */
internal fun SharedPreferences.getTimesById(key: String): Map<Int, EditTime> =
    getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split(':')
        val id = parts[0].toIntOrNull() ?: return@mapNotNull null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        id to EditTime(at, parts.getOrNull(2)?.toLongOrNull(), parts.getOrNull(3)?.toIntOrNull())
    }.toMap()

internal fun SharedPreferences.Editor.putTimesById(key: String, times: Map<Int, EditTime>): SharedPreferences.Editor =
    putStringSet(key, times.map { (id, at) -> "$id:${at.wallMs}:${at.elapsedMs ?: ""}:${at.boot ?: ""}" }.toSet())
