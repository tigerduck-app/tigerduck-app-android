package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences

// The holiday choices' preferences: sets of holiday ids, and the time each choice was made.

internal fun SharedPreferences.getIds(key: String): Set<Int> =
    getStringSet(key, emptySet())?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

internal fun SharedPreferences.Editor.putIds(key: String, ids: Set<Int>): SharedPreferences.Editor =
    putStringSet(key, ids.map(Int::toString).toSet())

/** "id:epochMs" entries; one that does not read is left out, as a time not kept. */
internal fun SharedPreferences.getTimesById(key: String): Map<Int, Long> =
    getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
        val id = entry.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        val at = entry.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
        id to at
    }.toMap()

internal fun SharedPreferences.Editor.putTimesById(key: String, times: Map<Int, Long>): SharedPreferences.Editor =
    putStringSet(key, times.map { (id, at) -> "$id:$at" }.toSet())
