package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.model.Assignment
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

data class BackendSyncResult(
    val assignments: List<Assignment>,
    val ignoredIds: Set<String>,
    val completedIds: Set<String>,
    val currentRevision: Long,
)

@Singleton
class SyncApiClient @Inject constructor(
    private val authTokenManager: AuthTokenManager,
) {
    private val baseUrl = authTokenManager.let {
        org.ntust.app.tigerduck.BuildConfig.PUSH_BASE_URL.trimEnd('/')
    }

    private val client = OkHttpClient.Builder().build()

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
            emptyList(), emptySet(), emptySet(), json.optLong("current_revision", 0)
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

        // Build PK → moodleAssignmentId map for override resolution
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
                val assignmentPk = o.optInt("user_assignment_id", -1)
                val moodleId = pkToMoodleId[assignmentPk] ?: continue
                when (status) {
                    "ignored", "archived" -> ignoredIds.add(moodleId)
                    "locally_completed" -> completedIds.add(moodleId)
                }
            }
        }

        return BackendSyncResult(
            assignments = assignments,
            ignoredIds = ignoredIds,
            completedIds = completedIds,
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
