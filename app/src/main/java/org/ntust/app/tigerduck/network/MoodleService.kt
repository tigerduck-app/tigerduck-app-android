package org.ntust.app.tigerduck.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleAssignmentsEnvelope
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoodleService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val tokenService: MoodleTokenService,
    private val courseService: CourseService,
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()
    private val webserviceUrl = "https://moodle2.ntust.edu.tw/webservice/rest/server.php"
    @Volatile private var cachedUserId: Int? = null

    /**
     * Fetch the user's enrolled Moodle courses across all semesters using
     * the long-lived Moodle Mobile wstoken. Matches iOS: calls the REST
     * webservice directly so we don't depend on the sesskey / `/my/` path,
     * which NTUST's edge (Citrix NetScaler) tends to challenge.
     */
    suspend fun fetchEnrolledCourses(studentId: String, password: String): List<MoodleEnrolledCourse> =
        withContext(Dispatchers.IO) {
            attemptWithTokenRetry { token ->
                val userId = getSiteInfoUserId(token)
                callEnrolledCourses(token, userId)
            }
        }

    /**
     * Fetch this semester's assignments with completion flags resolved from
     * Moodle. Mirrors the iOS pipeline (AppServiceBridge.swift):
     * `mod_assign_get_assignments` for the roster, then one
     * `mod_assign_get_submission_status` per assignment (fanned out in
     * parallel) to derive `isCompleted = (submission.status == "submitted")`.
     *
     * The caller passes in the already-fetched Moodle enrolment list so we
     * don't duplicate `core_enrol_get_users_courses` when the ViewModel has
     * just called `fetchEnrolledCourses` to build the course schedule.
     * Filtering by `semesterCode` is intentional — we used to intersect with
     * the NTUST course-selection roster too, but that silently dropped
     * anything enrolled via a non-standard path (勞作教育, 服務學習, 通識
     * sections, cross-enrolled extras, etc.).
     */
    suspend fun fetchAssignments(
        enrolledCourses: List<MoodleEnrolledCourse>,
    ): List<Assignment> = withContext(Dispatchers.IO) {
        val currentSemester = courseService.currentSemesterCode()
        val relevant = enrolledCourses.filter { it.semesterCode == currentSemester }
        if (relevant.isEmpty()) return@withContext emptyList<Assignment>()

        attemptWithTokenRetry { token ->
            val userId = getSiteInfoUserId(token)
            val envelope = callGetAssignments(token, relevant.map { it.id })
            val coursesById = relevant.associateBy { it.id }

            // Flatten the nested course→assignments response so we can fan
            // out submission-status calls keyed by assignId.
            val records = envelope.courses.flatMap { c ->
                c.assignments.map { a -> c.id to a }
            }

            val statuses = coroutineScope {
                records.map { (_, a) ->
                    async(Dispatchers.IO) {
                        runCatching { callGetSubmissionStatus(token, a.id, userId) }
                            .getOrNull()
                            ?.let { a.id to it }
                    }
                }.awaitAll().filterNotNull().toMap()
            }

            records.mapNotNull { (courseId, a) ->
                if (a.duedate <= 0) return@mapNotNull null
                // Info-only entries with no submission target — nothing to
                // complete or ignore; skip to match iOS.
                if (a.nosubmissions != 0) return@mapNotNull null
                val course = coursesById[courseId]
                val submission = statuses[a.id]?.lastattempt?.submission
                val submitted = submission?.status == "submitted"
                Assignment(
                    assignmentId = a.id.toString(),
                    courseNo = course?.courseNo ?: "",
                    courseName = parseCourseName(course?.fullname),
                    title = a.name,
                    dueDate = Date(a.duedate * 1000),
                    isCompleted = submitted,
                    moodleUrl = "https://moodle2.ntust.edu.tw/mod/assign/view.php?id=${a.cmid}",
                    cutoffDate = a.cutoffdate?.takeIf { it > 0 }?.let { Date(it * 1000) },
                    submittedAt = submission?.timemodified?.takeIf { it > 0 }?.let { Date(it * 1000) },
                )
            }
        }
    }

    /** Run [block] with current token; on `invalidtoken`, refresh once and retry. */
    private suspend inline fun <T> attemptWithTokenRetry(block: (String) -> T): T {
        val token = tokenService.currentToken() ?: tokenService.refreshToken()
        return try {
            block(token)
        } catch (e: MoodleWebserviceError.InvalidToken) {
            Log.w("MoodleService", "wstoken rejected, refreshing once")
            tokenService.clearToken()
            cachedUserId = null
            val fresh = tokenService.refreshToken()
            block(fresh)
        }
    }

    private fun getSiteInfoUserId(token: String): Int {
        cachedUserId?.let { return it }
        val url = "$webserviceUrl?moodlewsrestformat=json&wsfunction=core_webservice_get_site_info&wstoken=$token"
        val req = Request.Builder().url(url).post(FormBody.Builder().build()).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body?.string() ?: throw MoodleWebserviceError.MalformedResponse("site_info empty body")
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        val parsed = try {
            gson.fromJson(body, Map::class.java)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("site_info not JSON: ${e.message}")
        }
        val rawUserId = parsed?.get("userid")
            ?: throw MoodleWebserviceError.MalformedResponse("userid missing from site_info")
        val userId = (rawUserId as? Number)?.toInt()
            ?: throw MoodleWebserviceError.MalformedResponse("userid has unexpected type: $rawUserId")
        cachedUserId = userId
        return userId
    }

    private fun callEnrolledCourses(token: String, userId: Int): List<MoodleEnrolledCourse> {
        val url = "$webserviceUrl?moodlewsrestformat=json&wsfunction=core_enrol_get_users_courses&wstoken=$token"
        val form = FormBody.Builder().add("userid", userId.toString()).build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body?.string() ?: throw MoodleWebserviceError.MalformedResponse("enrolled empty body")
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        val type = object : TypeToken<List<MoodleEnrolledCourse>>() {}.type
        val courses: List<MoodleEnrolledCourse> = try {
            gson.fromJson(body, type)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("enrolled response not decodable: ${e.message}")
        } ?: emptyList()
        return courses
    }

    private fun callGetAssignments(token: String, courseIds: List<Int>): MoodleAssignmentsEnvelope {
        val url = "$webserviceUrl?moodlewsrestformat=json&wsfunction=mod_assign_get_assignments&wstoken=$token"
        val form = FormBody.Builder().apply {
            courseIds.forEachIndexed { i, id -> add("courseids[$i]", id.toString()) }
        }.build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body?.string() ?: throw MoodleWebserviceError.MalformedResponse("assignments empty body")
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        return try {
            gson.fromJson(body, MoodleAssignmentsEnvelope::class.java)
                ?: throw MoodleWebserviceError.MalformedResponse("assignments envelope null")
        } catch (e: MoodleWebserviceError) {
            throw e
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("assignments decode failed: ${e.message}")
        }
    }

    private fun callGetSubmissionStatus(token: String, assignId: Int, userId: Int): MoodleSubmissionStatusEnvelope {
        val url = "$webserviceUrl?moodlewsrestformat=json&wsfunction=mod_assign_get_submission_status&wstoken=$token"
        val form = FormBody.Builder()
            .add("assignid", assignId.toString())
            .add("userid", userId.toString())
            .build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body?.string() ?: throw MoodleWebserviceError.MalformedResponse("submission status empty body")
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        return try {
            gson.fromJson(body, MoodleSubmissionStatusEnvelope::class.java)
                ?: throw MoodleWebserviceError.MalformedResponse("submission status envelope null")
        } catch (e: MoodleWebserviceError) {
            throw e
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("submission status decode failed: ${e.message}")
        }
    }

    /**
     * Moodle course fullname is typically "1142 PE139B022 課程名稱 / English".
     * Strip the semester + course-number prefix to recover the Chinese name.
     * Mirrors the iOS `courseName(from:)` helper.
     */
    private fun parseCourseName(fullname: String?): String {
        if (fullname.isNullOrBlank()) return ""
        val parts = fullname.split(" ")
        val courseNoRegex = Regex("3?[A-Z]{2}[A-Z0-9]{6,7}")
        val idx = parts.indexOfFirst { courseNoRegex.containsMatchIn(it) }
        return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else fullname
    }
}
