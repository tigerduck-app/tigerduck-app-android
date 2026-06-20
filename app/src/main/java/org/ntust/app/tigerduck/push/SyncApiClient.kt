package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

data class CourseOverrideResult(
    val moodleCourseId: String,
    val courseNo: String?,
    val colorHex: String?,
    val customNames: Map<String, String> = emptyMap(),
)

data class ServerCourse(
    val courseNo: String,
    val courseName: String,
    val semester: String,
    val instructors: List<String> = emptyList(),
    val credits: Int = 0,
    val classroom: String = "",
    val enrolledCount: Int = 0,
    val maxCount: Int = 0,
    val moodleId: String? = null,
)

data class BackendSyncResult(
    val assignments: List<Assignment>,
    val ignoredIds: Set<String>,
    val completedIds: Set<String>,
    val courseOverrides: List<CourseOverrideResult>,
    val serverCourseNos: Set<String>,
    val serverCourses: List<ServerCourse> = emptyList(),
    val currentRevision: Long,
)

@Singleton
class SyncApiClient @Inject constructor(
    private val authTokenManager: AuthTokenManager,
    baseClient: OkHttpClient,
) {
    private val baseUrl = org.ntust.app.tigerduck.BuildConfig.PUSH_BASE_URL.trimEnd('/')

    private val client = baseClient.newBuilder().build()

    suspend fun fetchFullSync(): BackendSyncResult = withContext(Dispatchers.IO) {
        val authHeader = authTokenManager.authHeader()
            ?: throw PushApiException("not authenticated")
        val request = Request.Builder()
            .url("$baseUrl/sync/full")
            .get()
            .header("Authorization", authHeader)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PushApiException("sync/full failed: HTTP ${response.code}")
            }
            val json = JSONObject(response.body.string())
            parseFullSync(json)
        }
    }

    private fun parseFullSync(json: JSONObject): BackendSyncResult {
        val assignments = mutableListOf<Assignment>()
        val arr = json.optJSONArray("assignments") ?: return BackendSyncResult(
            emptyList(), emptySet(), emptySet(), emptyList(), emptySet(), json.optLong("current_revision", 0)
        )
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (!a.isNull("deleted_at")) continue
            val dueDate = nullStr(a, "due_at")?.let { parseIso(it) }
                ?: Date(Long.MAX_VALUE)
            assignments.add(
                Assignment(
                    assignmentId = a.getInt("moodle_assignment_id").toString(),
                    courseNo = nullStr(a, "course_no") ?: "",
                    courseName = nullStr(a, "course_name") ?: "",
                    title = nullStr(a, "title") ?: "",
                    dueDate = dueDate,
                    isCompleted = a.optBoolean("provider_is_submitted", false),
                    moodleUrl = nullStr(a, "moodle_url"),
                    cutoffDate = nullStr(a, "cutoff_at")?.let { parseIso(it) },
                    submittedAt = nullStr(a, "provider_submitted_at")?.let { parseIso(it) },
                )
            )
        }

        // PK → moodleId fallback for servers that don't include moodle_assignment_id in overrides
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
                    serverCourses.add(ServerCourse(
                        courseNo = courseNo,
                        courseName = nullStr(c, "course_name") ?: courseNo,
                        semester = nullStr(c, "semester") ?: "",
                        instructors = instructors,
                        credits = c.optDouble("credits", 0.0).toInt(),
                        classroom = nullStr(c, "classroom") ?: "",
                        enrolledCount = c.optInt("enrolled_count", 0),
                        maxCount = c.optInt("max_count", 0),
                        moodleId = nullStr(c, "moodle_id"),
                    ))
                }
                val mId = nullStr(c, "moodle_id") ?: continue
                val cName = nullStr(c, "course_name") ?: continue
                val bracketEnd = cName.indexOf("】")
                if (bracketEnd >= 0) {
                    val rest = cName.substring(bracketEnd + 1).trim()
                    val code = rest.split(" ", limit = 2).firstOrNull()?.takeIf { it.isNotEmpty() }
                    if (code != null) courseMoodleIdToNo[mId] = code
                }
            }
        }

        val courseOverrides = mutableListOf<CourseOverrideResult>()
        val courseOverArr = json.optJSONArray("course_overrides")
        if (courseOverArr != null) {
            for (i in 0 until courseOverArr.length()) {
                val co = courseOverArr.getJSONObject(i)
                val moodleId = nullStr(co, "moodle_id") ?: continue
                val namesObj = co.optJSONObject("custom_names")
                val names = mutableMapOf<String, String>()
                if (namesObj != null) {
                    for (key in namesObj.keys()) {
                        val v = namesObj.optString(key, "")
                        if (v.isNotEmpty()) names[key] = v
                    }
                }
                courseOverrides.add(CourseOverrideResult(
                    moodleCourseId = moodleId,
                    courseNo = courseMoodleIdToNo[moodleId],
                    colorHex = nullStr(co, "color_hex"),
                    customNames = names,
                ))
            }
        }

        return BackendSyncResult(
            assignments = assignments,
            ignoredIds = ignoredIds,
            completedIds = completedIds,
            courseOverrides = courseOverrides,
            serverCourseNos = serverCourseNos,
            serverCourses = serverCourses,
            currentRevision = json.optLong("current_revision", 0),
        )
    }

    private fun nullStr(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val v = obj.optString(key, "")
        return v.ifBlank { null }
    }

    private fun parseIso(s: String): Date? {
        if (s.isBlank() || s == "null") return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(s.take(19))
        } catch (_: Exception) {
            null
        }
    }
}
