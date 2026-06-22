package org.ntust.app.tigerduck.push

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.lang.reflect.Type
import java.util.Date
import java.util.UUID

// ---------------------------------------------------------------------------
// SyncOp — pending local change waiting to be pushed to the server.
// ---------------------------------------------------------------------------

sealed class SyncOp {
    abstract val dedupKey: String

    data class CourseOverride(
        val semester: String,
        val courseKey: String,
        val customName: String?,
        val colorHex: String?,
        val stamp: Date,
    ) : SyncOp() {
        override val dedupKey: String
            get() {
                val fields = buildString {
                    if (customName != null) append("n")
                    if (colorHex != null) append("c")
                }
                return "co|$semester|$courseKey|$fields"
            }
    }

    data class AssignmentOverride(
        val moodleCourseId: Int,
        val moodleAssignmentId: Int,
        val localStatus: String,
        val stamp: Date,
    ) : SyncOp() {
        override val dedupKey: String
            get() = "ao|$moodleCourseId|$moodleAssignmentId"
    }

    data object UploadSnapshot : SyncOp() {
        override val dedupKey: String get() = "upload"
    }
}

// ---------------------------------------------------------------------------
// ResolvedSyncOp — what the executor receives after server IDs are resolved.
// ---------------------------------------------------------------------------

sealed class ResolvedSyncOp {
    data class CourseOverride(
        val courseId: String,
        val colorHex: String?,
        val customName: String?,
        val locale: String?,
    ) : ResolvedSyncOp()

    data class AssignmentOverride(
        val assignmentId: Int,
        val localStatus: String,
    ) : ResolvedSyncOp()

    data object UploadSnapshot : ResolvedSyncOp()
}

// ---------------------------------------------------------------------------
// OutboxEntry
// ---------------------------------------------------------------------------

data class OutboxEntry(
    val id: String = UUID.randomUUID().toString(),
    val op: SyncOp,
    var attempts: Int = 0,
)

// ---------------------------------------------------------------------------
// SyncOutbox — persisted outbound-operation queue.
// ---------------------------------------------------------------------------

class SyncOutbox(context: Context) {

    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SyncOp::class.java, SyncOpAdapter())
        .create()
    private var entries: MutableList<OutboxEntry> = mutableListOf()

    init {
        entries = loadEntries()
    }

    suspend fun enqueue(op: SyncOp) = mutex.withLock {
        val key = op.dedupKey
        entries.removeAll { it.op.dedupKey == key }
        entries.add(OutboxEntry(op = op))
        persist()
    }

    suspend fun pendingCount(): Int = mutex.withLock { entries.size }

    suspend fun clearAll() = mutex.withLock {
        entries.clear()
        persist()
    }

    /**
     * Process entries in order via the provided executor.
     * Returns `true` if the queue is empty at the end.
     *
     * Error handling:
     * - Success: remove entry
     * - 401 / auth error: abort drain, keep all remaining entries
     * - IOException (network): abort drain, don't increment attempts
     * - Other error: increment attempts, drop after [MAX_ATTEMPTS] failures
     */
    suspend fun drain(
        idMap: SyncIdMap,
        execute: suspend (ResolvedSyncOp) -> Unit,
    ): Boolean = mutex.withLock {
        val kept = mutableListOf<OutboxEntry>()
        var index = 0

        while (index < entries.size) {
            val entry = entries[index]

            val resolved = resolve(entry.op, idMap)
            if (resolved == null) {
                // Can't resolve (missing server ID) — keep for later.
                kept.add(entry)
                index++
                continue
            }

            try {
                execute(resolved)
                // Success — entry consumed, don't add to kept.
            } catch (e: SyncOutboxAuthException) {
                // 401: abort drain, keep this and all remaining entries.
                kept.add(entry)
                kept.addAll(entries.subList(index + 1, entries.size))
                entries = kept
                persist()
                return@withLock false
            } catch (e: IOException) {
                // Network error: abort drain, don't increment attempts.
                kept.add(entry)
                kept.addAll(entries.subList(index + 1, entries.size))
                entries = kept
                persist()
                return@withLock false
            } catch (e: Exception) {
                // Other error: increment attempts, drop after MAX_ATTEMPTS.
                entry.attempts++
                if (entry.attempts < MAX_ATTEMPTS) {
                    kept.add(entry)
                } else {
                    Log.w(TAG, "Outbox: dropping entry ${entry.id} after $MAX_ATTEMPTS attempts — key: ${entry.op.dedupKey}")
                }
            }

            index++
        }

        entries = kept
        persist()
        entries.isEmpty()
    }

    // -- Resolution -------------------------------------------------------

    private fun resolve(op: SyncOp, idMap: SyncIdMap): ResolvedSyncOp? = when (op) {
        is SyncOp.CourseOverride -> {
            val moodleId = "client:${op.semester}:${op.courseKey}"
            val locale = if (op.customName != null) java.util.Locale.getDefault().language else null
            ResolvedSyncOp.CourseOverride(
                courseId = moodleId,
                colorHex = op.colorHex,
                customName = op.customName,
                locale = locale,
            )
        }

        is SyncOp.AssignmentOverride -> {
            ResolvedSyncOp.AssignmentOverride(
                assignmentId = op.moodleAssignmentId,
                localStatus = op.localStatus,
            )
        }

        is SyncOp.UploadSnapshot -> ResolvedSyncOp.UploadSnapshot
    }

    // -- Persistence ------------------------------------------------------

    private fun persist() {
        val type = object : TypeToken<List<OutboxEntry>>() {}.type
        val json = gson.toJson(entries, type)
        prefs.edit().putString(KEY_ENTRIES, json).apply()
    }

    private fun loadEntries(): MutableList<OutboxEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<OutboxEntry>>() {}.type
            gson.fromJson<MutableList<OutboxEntry>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "outbox JSON decode failed — starting empty", e)
            mutableListOf()
        }
    }

    companion object {
        private const val TAG = "CloudSync.Outbox"
        private const val PREFS_NAME = "cloud_sync_outbox"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ATTEMPTS = 5
    }
}

/** Thrown by the executor when the API returns 401 so the outbox drain aborts. */
class SyncOutboxAuthException(val statusCode: Int) : Exception("Auth error: HTTP $statusCode")

// ---------------------------------------------------------------------------
// Gson adapter for sealed SyncOp hierarchy
// ---------------------------------------------------------------------------

private class SyncOpAdapter : JsonSerializer<SyncOp>, JsonDeserializer<SyncOp> {

    override fun serialize(src: SyncOp, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        when (src) {
            is SyncOp.CourseOverride -> {
                obj.addProperty("type", "courseOverride")
                obj.addProperty("semester", src.semester)
                obj.addProperty("courseKey", src.courseKey)
                if (src.customName != null) obj.addProperty("customName", src.customName)
                if (src.colorHex != null) obj.addProperty("colorHex", src.colorHex)
                obj.addProperty("stamp", src.stamp.time)
            }
            is SyncOp.AssignmentOverride -> {
                obj.addProperty("type", "assignmentOverride")
                obj.addProperty("moodleCourseId", src.moodleCourseId)
                obj.addProperty("moodleAssignmentId", src.moodleAssignmentId)
                obj.addProperty("localStatus", src.localStatus)
                obj.addProperty("stamp", src.stamp.time)
            }
            is SyncOp.UploadSnapshot -> {
                obj.addProperty("type", "uploadSnapshot")
            }
        }
        return obj
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): SyncOp {
        val obj = json.asJsonObject
        return when (obj.get("type").asString) {
            "courseOverride" -> SyncOp.CourseOverride(
                semester = obj.get("semester").asString,
                courseKey = obj.get("courseKey").asString,
                customName = obj.get("customName")?.takeIf { !it.isJsonNull }?.asString,
                colorHex = obj.get("colorHex")?.takeIf { !it.isJsonNull }?.asString,
                stamp = Date(obj.get("stamp").asLong),
            )
            "assignmentOverride" -> SyncOp.AssignmentOverride(
                moodleCourseId = obj.get("moodleCourseId").asInt,
                moodleAssignmentId = obj.get("moodleAssignmentId").asInt,
                localStatus = obj.get("localStatus").asString,
                stamp = Date(obj.get("stamp").asLong),
            )
            "uploadSnapshot" -> SyncOp.UploadSnapshot
            else -> throw IllegalArgumentException("Unknown SyncOp type: ${obj.get("type")}")
        }
    }
}
