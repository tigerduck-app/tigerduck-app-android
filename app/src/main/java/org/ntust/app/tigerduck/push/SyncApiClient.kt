package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.guardedBy
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.shared.Course
import javax.inject.Inject
import javax.inject.Singleton

data class CourseOverrideResult(
    val moodleCourseId: String,
    val courseNo: String?,
    val colorHex: String?,
    /** Per language; an empty name is one the user cleared. */
    val customNames: Map<String, String> = emptyMap(),
    /** The term of the course row the override belongs to. */
    val semester: String? = null,
    /**
     * Whether every assignment of the course is ignored. Null when the
     * backend did not send the field, which a backend older than it does.
     */
    val ignoreAssignments: Boolean? = null,
    /**
     * When a device last set [ignoreAssignments], either way. Null on a row
     * made for a colour or a name, or remade after the backend dropped it
     * with its course row.
     */
    val ignoreAssignmentsUpdatedAt: String? = null,
    /** The course's numeric Moodle id, once a client has sent it. */
    val moodleNumericCourseId: Int? = null,
    /**
     * When a device last set [colorHex], as the backend's ISO-8601 time.
     * Null from a backend that does not send it, and on a row whose colour
     * came with an upload rather than a pick.
     */
    val colorHexUpdatedAt: String? = null,
)

data class ServerCourse(
    val courseNo: String,
    val courseName: String,
    val semester: String,
    val instructors: List<String> = emptyList(),
    val credits: Float = 0f,
    val classroom: String = "",
    val enrolledCount: Int = 0,
    val maxCount: Int = 0,
    val moodleId: String? = null,
    val scheduleJson: String = "{}",
    val classroomMapJson: String = "{}",
    val version: Int = 1,
    val updatedAt: String? = null,
    /**
     * The server row's id. A course keeps it through every upload and gets a
     * new one only when its row is deleted and made again; see
     * `CourseIgnoreReconciler.recreated`. Null from a snapshot without it.
     */
    val id: Long? = null,
)

data class CourseTombstone(
    val courseKey: String,
    val courseNo: String?,
    val semester: String,
    val deletedAt: String,
    /** Written by a semester reset rather than a single delete. */
    val deletedByReset: Boolean = false,
    /** Written by the device this snapshot was served to. */
    val deletedByThisDevice: Boolean = false,
)

data class BackendSyncResult(
    val ignoredIds: Set<String>,
    val completedIds: Set<String>,
    val courseOverrides: List<CourseOverrideResult>,
    val serverCourseNos: Set<String>,
    val serverCourses: List<ServerCourse> = emptyList(),
    val currentRevision: Long,
    val coursesResetAt: String? = null,
    val tombstones: List<CourseTombstone> = emptyList(),
    /** When the request went out (epoch millis) — what the snapshot is as of. */
    val fetchedAtMs: Long = 0L,
    /**
     * Holidays the user opted back into on some device. Null means the
     * server did not send the section at all — an older backend — which is
     * not the same as "the user has none", so the local set is left alone.
     */
    val holidayOverrides: Set<Int>? = null,
)

// The change feed's answer when the changes after the asked revision are gone.
private const val HTTP_GONE = 410

/** One entry of the change feed: only what a poll's plan reads (`ChangeFeedPlan`). */
data class SyncChange(val revision: Long, val entityType: String)

/**
 * One page of `GET /sync?since_revision=`, oldest change first, as the backend
 * answers it; as the iPhone's `PushAPI.SyncChangesPage`.
 */
data class SyncChangesPage(
    val currentRevision: Long,
    val returnedUntilRevision: Long,
    val hasMore: Boolean,
    val changes: List<SyncChange>,
)

/**
 * A page as read off the wire. A field missing or a change unreadable reads as
 * what makes the poll run a full sync (more to come, revision 0, no entity
 * type), never as a change it may pass over.
 */
internal fun parseChangesPage(json: JSONObject): SyncChangesPage {
    val changes = json.optJSONArray("changes")
    return SyncChangesPage(
        currentRevision = json.optLong("current_revision", 0),
        returnedUntilRevision = json.optLong("returned_until_revision", 0),
        hasMore = json.optBoolean("has_more", true),
        changes = (0 until (changes?.length() ?: 0)).map { i ->
            val change = changes?.optJSONObject(i)
            SyncChange(change?.optLong("revision", 0) ?: 0, change?.optString("entity_type").orEmpty())
        },
    )
}

@Singleton
class SyncApiClient @Inject constructor(
    private val authTokenManager: AuthTokenManager,
    private val prefs: AppPreferences,
    baseClient: OkHttpClient,
) {
    // Resolve per-call so the debug API-endpoint override applies immediately,
    // matching PushApiClient — pinning at construction made auth/full-sync
    // traffic ignore an override that push traffic honored.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')

    private val client = baseClient.newBuilder()
        .guardedBy(authTokenManager)
        .build()

    private val api = BackendServices(SyncApi::class.java, client)

    suspend fun fetchRevision(): Long = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader()
        api.at(baseUrl).revision(authHeader).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("sync/revision failed: HTTP ${response.code}", response.code)
            }
            val json = JSONObject(response.body.string())
            json.optLong("revision", 0)
        }
    }

    /**
     * The changes after [sinceRevision], one page of them; null when the server
     * no longer keeps them (410 `sync_revision_expired`), so only a full sync
     * can catch up. See `ChangeFeedPlan`.
     */
    suspend fun fetchChanges(sinceRevision: Long): SyncChangesPage? = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader()
        api.at(baseUrl).changes(authHeader, sinceRevision).execute().use { response ->
            if (response.code == HTTP_GONE) return@withContext null
            if (!response.isSuccessful) {
                throw PushApiException("sync changes failed: HTTP ${response.code}", response.code)
            }
            parseChangesPage(JSONObject(response.body.string()))
        }
    }

    suspend fun fetchFullSync(): BackendSyncResult = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.requireAuthHeader()
        val fetchedAtMs = System.currentTimeMillis()
        api.at(baseUrl).full(authHeader).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("sync/full failed: HTTP ${response.code}")
            }
            val json = JSONObject(response.body.string())
            parseFullSync(json).copy(fetchedAtMs = fetchedAtMs)
        }
    }

    private fun parseFullSync(json: JSONObject): BackendSyncResult {
        // The snapshot's assignment rows are not read as assignments: every
        // cached assignment comes from Moodle itself (MoodleService). They
        // only key the overrides below by server row id, for a server whose
        // overrides carry no moodle_assignment_id of their own.
        val arr = json.optJSONArray("assignments") ?: return BackendSyncResult(
            emptySet(), emptySet(), emptyList(), emptySet(), emptyList(), json.optLong("current_revision", 0)
        )
        val pkToMoodleId = mutableMapOf<Int, String>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val pk = a.optInt("id", -1)
            val mid = a.optInt("moodle_assignment_id", -1)
            if (pk > 0 && mid > 0) pkToMoodleId[pk] = mid.toString()
        }

        val ignoredIds = mutableSetOf<String>()
        val completedIds = mutableSetOf<String>()
        val overArr = json.optJSONArray("assignment_overrides")
        if (overArr != null) {
            for (i in 0 until overArr.length()) {
                val o = overArr.getJSONObject(i)
                val status = o.optString("local_status", "none")
                val mid = o.optInt("moodle_assignment_id", -1)
                val moodleId = if (mid > 0) mid.toString()
                    else pkToMoodleId[o.optInt("user_assignment_id", -1)] ?: continue
                when (status) {
                    "ignored", "archived" -> ignoredIds.add(moodleId)
                    "locally_completed" -> completedIds.add(moodleId)
                }
            }
        }

        // Build moodleId → courseNo from courses array for override resolution,
        // and collect the full set of server-side course_nos for deletion detection.
        val courseMoodleIdToNo = mutableMapOf<String, String>()
        val serverCourseNos = mutableSetOf<String>()
        val serverCourses = mutableListOf<ServerCourse>()
        val coursesArr = json.optJSONArray("courses")
        if (coursesArr != null) {
            for (i in 0 until coursesArr.length()) {
                val c = coursesArr.getJSONObject(i)
                val courseNo = nullStr(c, "course_no")
                if (courseNo != null) {
                    serverCourseNos.add(courseNo)
                    val instructors = mutableListOf<String>()
                    val instrArr = c.optJSONArray("instructors")
                    if (instrArr != null) {
                        for (j in 0 until instrArr.length()) {
                            instrArr.optString(j)?.takeIf { it.isNotEmpty() }?.let { instructors.add(it) }
                        }
                    }
                    val schedObj = c.optJSONObject("schedule_json")
                    val schedStr = schedObj?.toString() ?: "{}"
                    val cmapObj = c.optJSONObject("classroom_map")
                    val cmapStr = cmapObj?.toString() ?: "{}"
                    serverCourses.add(ServerCourse(
                        courseNo = courseNo,
                        courseName = nullStr(c, "course_name") ?: courseNo,
                        semester = nullStr(c, "semester") ?: "",
                        instructors = instructors,
                        credits = c.optDouble("credits", 0.0).toFloat(),
                        classroom = nullStr(c, "classroom") ?: "",
                        enrolledCount = c.optInt("enrolled_count", 0),
                        maxCount = c.optInt("max_count", 0),
                        moodleId = nullStr(c, "moodle_id"),
                        scheduleJson = schedStr,
                        classroomMapJson = cmapStr,
                        version = c.optInt("version", 1),
                        updatedAt = nullStr(c, "updated_at"),
                        id = serverCourseRowId(c),
                    ))
                }
                val mId = nullStr(c, "moodle_id") ?: continue
                if (courseNo != null) {
                    courseMoodleIdToNo[mId] = courseNo
                } else {
                    val cName = nullStr(c, "course_name") ?: continue
                    val bracketEnd = cName.indexOf("】")
                    if (bracketEnd >= 0) {
                        val rest = cName.substring(bracketEnd + 1).trim()
                        val code = rest.split(" ", limit = 2).firstOrNull()?.takeIf { it.isNotEmpty() }
                        if (code != null) courseMoodleIdToNo[mId] = code
                    }
                }
            }
        }

        Log.d("SyncApi", "[fullSync] courseMoodleIdToNo: ${courseMoodleIdToNo.size} entries, courses: ${serverCourses.size}")

        val courseOverrides = parseCourseOverrides(json, courseMoodleIdToNo)

        val coursesResetAt = nullStr(json, "courses_reset_at")
        val tombstones = mutableListOf<CourseTombstone>()
        val tombArr = json.optJSONArray("course_tombstones")
        if (tombArr != null) {
            for (i in 0 until tombArr.length()) {
                val t = tombArr.getJSONObject(i)
                tombstones.add(CourseTombstone(
                    courseKey = t.optString("course_key", ""),
                    courseNo = nullStr(t, "course_no"),
                    semester = t.optString("semester", ""),
                    deletedAt = t.optString("deleted_at", ""),
                    deletedByReset = t.optBoolean("deleted_by_reset", false),
                    deletedByThisDevice = t.optBoolean("deleted_by_this_device", false),
                ))
            }
        }

        return BackendSyncResult(
            ignoredIds = ignoredIds,
            completedIds = completedIds,
            courseOverrides = courseOverrides,
            serverCourseNos = serverCourseNos,
            serverCourses = serverCourses,
            currentRevision = json.optLong("current_revision", 0),
            coursesResetAt = coursesResetAt,
            tombstones = tombstones,
            holidayOverrides = parseHolidayOverrides(json),
        )
    }

    /** `notify: false` rows are stored server-side so a device can tell
     *  "turned it off" from "never set it"; only the true ones belong in the
     *  opted-in set. */
    private fun parseHolidayOverrides(json: JSONObject): Set<Int>? {
        val arr = json.optJSONArray("holiday_overrides") ?: return null
        val out = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (row.optBoolean("notify", false)) out.add(row.optInt("holiday_id", -1))
        }
        out.remove(-1)
        return out
    }

    private fun nullStr(obj: JSONObject, key: String): String? = nullableString(obj, key)
}

/** A snapshot course row's `id`, or null when it has none. */
internal fun serverCourseRowId(row: JSONObject): Long? =
    if (row.isNull("id")) null else row.optLong("id", 0L).takeIf { it > 0 }

/**
 * The snapshot's `course_overrides`. [courseMoodleIdToNo] names the course of
 * an override that carries no `course_no` of its own.
 */
internal fun parseCourseOverrides(
    json: JSONObject,
    courseMoodleIdToNo: Map<String, String>,
): List<CourseOverrideResult> {
    val rows = json.optJSONArray("course_overrides") ?: return emptyList()
    val overrides = mutableListOf<CourseOverrideResult>()
    for (i in 0 until rows.length()) {
        val co = rows.optJSONObject(i) ?: continue
        val moodleId = nullableString(co, "moodle_id") ?: continue
        val namesObj = co.optJSONObject("custom_names")
        val names = mutableMapOf<String, String>()
        // Kept when empty: "" is a name the user cleared (tigerduck-backend#122),
        // which the merge takes off this phone too.
        if (namesObj != null) {
            for (key in namesObj.keys()) {
                names[key] = namesObj.optString(key, "")
            }
        }
        val resolvedNo = nullableString(co, "course_no") ?: courseMoodleIdToNo[moodleId]
        val colorHex = nullableString(co, "color_hex")
        Log.d("SyncApi", "[fullSync] override moodleId=$moodleId courseNo=$resolvedNo colorHex=$colorHex names=${names.size}")
        overrides.add(
            CourseOverrideResult(
                moodleCourseId = moodleId,
                courseNo = resolvedNo,
                colorHex = colorHex,
                customNames = names,
                semester = nullableString(co, "semester"),
                ignoreAssignments = if (co.isNull("ignore_assignments")) {
                    null
                } else {
                    co.optBoolean("ignore_assignments")
                },
                ignoreAssignmentsUpdatedAt = nullableString(co, "ignore_assignments_updated_at"),
                moodleNumericCourseId = co.optLong("moodle_course_id", 0L)
                    .takeIf { it in 1..Int.MAX_VALUE }
                    ?.toInt(),
                colorHexUpdatedAt = nullableString(co, "color_hex_updated_at"),
            )
        )
    }
    return overrides
}

private fun nullableString(obj: JSONObject, key: String): String? {
    if (obj.isNull(key)) return null
    val v = obj.optString(key, "")
    return v.ifBlank { null }
}
