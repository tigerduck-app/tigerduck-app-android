package org.ntust.app.tigerduck.data.cache

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Writes [value] to [file] under the Moodle cache's schema number
 * ([DataCache.MOODLE_CACHE_SCHEMA]), with when it was fetched. The caller holds the lock of the
 * folder [file] is in.
 */
internal fun writeMoodleEnvelope(gson: Gson, file: File, value: Any, fetchedAtMs: Long) {
    val envelope = JsonObject().apply {
        addProperty("v", DataCache.MOODLE_CACHE_SCHEMA)
        addProperty("fetchedAt", fetchedAtMs)
        add("value", gson.toJsonTree(value))
    }
    file.parentFile?.mkdirs()
    atomicWrite(file, gson.toJson(envelope))
}

/** What [writeMoodleEnvelope] wrote to [file]; null for a file in another shape, or none. */
internal fun <T> readMoodleEnvelope(gson: Gson, file: File, type: java.lang.reflect.Type): DataCache.MoodleCached<T>? = try {
    if (!file.exists()) {
        null
    } else {
        val envelope = JsonParser.parseString(file.readText()).asJsonObject
        if (envelope.get("v")?.asInt != DataCache.MOODLE_CACHE_SCHEMA) {
            null
        } else {
            gson.fromJson<T>(envelope.get("value"), type)?.let { DataCache.MoodleCached(it, envelope.get("fetchedAt").asLong) }
        }
    }
} catch (_: Exception) {
    null
}
