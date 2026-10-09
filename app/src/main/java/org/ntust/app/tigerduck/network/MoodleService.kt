package org.ntust.app.tigerduck.network

import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.model.MoodleAssignmentsEnvelope
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import org.ntust.app.tigerduck.util.SharedFetch
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoodleService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val tokenService: MoodleTokenService,
    private val courseService: CourseService,
    private val dataCache: DataCache,
    private val prefs: AppPreferences,
    @param:ApplicationScope appScope: CoroutineScope,
) {
    private val client: OkHttpClient get() = sessionManager.client
    private val gson = Gson()
    private val webserviceUrl = "https://moodle2.ntust.edu.tw/webservice/rest/server.php"

    @Volatile
    private var cachedUserId: Int? = null
    private val siteInfoLock = Any()

    // Home, the class table, the calendar and the worker each ask for these,
    // within seconds of each other on launch — see SharedFetch. Keyed by the
    // wstoken, so one account's answer never reaches another's.
    private val sharedEnrolled = SharedFetch<String, List<MoodleEnrolledCourse>>(
        appScope, SHARED_ANSWER_WINDOW_MS, SystemClock::elapsedRealtime,
    )
    private val sharedAssignments = SharedFetch<AssignmentsKey, AssignmentsRound>(
        appScope, SHARED_ANSWER_WINDOW_MS, SystemClock::elapsedRealtime,
    )

    private data class AssignmentsKey(val token: String, val courseIds: List<Int>)

    /** The network half of [fetchAssignments]: what every caller asking for the same courses can share. */
    private class AssignmentsRound(
        val envelope: MoodleAssignmentsEnvelope,
        val statuses: Map<Int, MoodleSubmissionStatusEnvelope>,
        /** Submissions the cache had confirmed, not asked about — see [confirmedSubmissions]. */
        val confirmed: Map<Int, Date?>,
    )

    // Set by a pull to refresh and taken by the next round, which then asks
    // about every assignment, the confirmed ones included.
    @Volatile
    private var recheckConfirmed = false

    /**
     * Drops the answers kept for sharing, so the next fetch asks Moodle
     * again. For a refresh the user pulled for: they may have just submitted
     * something, and an answer from a minute ago would show it outstanding.
     */
    fun expireSharedResults() {
        sharedEnrolled.expire()
        sharedAssignments.expire()
        recheckConfirmed = true
    }

    /**
     * Fetch the user's enrolled Moodle courses across all semesters using
     * the long-lived Moodle Mobile wstoken. Matches iOS: calls the REST
     * webservice directly so we don't depend on the sesskey / `/my/` path,
     * which NTUST's edge (Citrix NetScaler) tends to challenge.
     */
    suspend fun fetchEnrolledCourses(): List<MoodleEnrolledCourse> =
        shared(sharedEnrolled, tokenService.currentToken()) {
            withContext(Dispatchers.IO) {
                attemptWithTokenRetry { token ->
                    val userId = getSiteInfoUserId(token)
                    callEnrolledCourses(token, userId)
                }
            }
        }

    /**
     * [fetch] through [sharedFetch] under the account's wstoken, or on its
     * own when there is no token to key it by. A sign-in fetches before its
     * token has been harvested, and an answer kept under a blank key would
     * reach whoever signed in next within the window.
     */
    private suspend fun <K : Any, V> shared(
        sharedFetch: SharedFetch<K, V>,
        token: String?,
        key: (String) -> K,
        fetch: suspend () -> V,
    ): V = if (token.isNullOrEmpty()) fetch() else sharedFetch.get(key(token), fetch)

    private suspend fun <V> shared(
        sharedFetch: SharedFetch<String, V>,
        token: String?,
        fetch: suspend () -> V,
    ): V = shared(sharedFetch, token, { it }, fetch)

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
     * Mirrors iOS: filter to the current in-app roster when available (so
     * dropped courses are excluded), else fall back to semesterCode filtering
     * on first launch when the roster cache is still empty.
     */
    suspend fun fetchAssignments(
        enrolledCourses: List<MoodleEnrolledCourse>,
        rosterCourseNos: Set<String>? = null,
    ): List<Assignment> = withContext(Dispatchers.IO) {
        val currentSemester = courseService.currentSemesterCode()
        // What the class table holds right now, hand-added rows included. It
        // decides which of a 合開 course's codes an assignment is filed under.
        val cachedCourseNos = dataCache.loadCourses().map { it.courseNo }.toSet()
        val rosterNos = rosterCourseNos ?: cachedCourseNos
        // Matched on every code a course answers to (MoodleCourseIds.forRoster):
        // the roster holds the student's own department's code, which for a
        // co-listed course is not always the one in its idnumber.
        val relevant = if (rosterNos.isEmpty()) {
            enrolledCourses.filter { it.semesterCode == currentSemester }
        } else {
            MoodleCourseIds.forRoster(enrolledCourses, rosterNos)
        }
        if (relevant.isEmpty()) return@withContext emptyList<Assignment>()
        val localCourseNos = rosterNos + cachedCourseNos
        // Once per course rather than once per assignment: the alias scan
        // runs a regex over the fullname.
        val courseNoById = relevant.associate {
            it.id to MoodleCourseIds.assignmentCourseNo(it, localCourseNos)
        }

        // Keyed on the courses, not on what each caller makes of them: the
        // filing above is the caller's own, the requests are the same.
        val courseIds = relevant.map { it.id }.sorted()
        val round = shared(
            sharedAssignments,
            tokenService.currentToken(),
            key = { AssignmentsKey(it, courseIds) },
        ) { fetchAssignmentsRound(courseIds) }
        val coursesById = relevant.associateBy { it.id }

        // Flatten the nested course→assignments response.
        round.envelope.courses
            .flatMap { c -> c.assignments.map { a -> c.id to a } }
            .mapNotNull { (courseId, a) ->
                if (a.duedate <= 0) return@mapNotNull null
                // Info-only entries with no submission target — nothing to
                // complete or ignore; skip to match iOS.
                if (a.nosubmissions != 0) return@mapNotNull null
                val course = coursesById[courseId]
                val (submitted, submittedAt) = submissionState(
                    a.id, round.confirmed, round.statuses[a.id]?.lastattempt?.submission,
                )
                Assignment(
                    assignmentId = a.id.toString(),
                    courseNo = courseNoById[courseId] ?: "",
                    courseName = parseCourseName(course?.fullname).decodeHtmlEntities(),
                    title = a.name.decodeHtmlEntities(),
                    dueDate = Date(a.duedate * 1000),
                    isCompleted = submitted,
                    moodleUrl = "https://moodle2.ntust.edu.tw/mod/assign/view.php?id=${a.cmid}",
                    cutoffDate = a.cutoffdate?.takeIf { it > 0 }?.let { Date(it * 1000) },
                    submittedAt = submittedAt,
                )
            }
    }

    /**
     * `mod_assign_get_assignments` for [courseIds], then one
     * `mod_assign_get_submission_status` per assignment the cache has not
     * already confirmed, fanned out in parallel. A status call that fails is
     * left out of the map, which the caller reads as "not submitted" — see
     * [org.ntust.app.tigerduck.data.CourseRosterMerge.preserveConfirmedSubmissions].
     *
     * The confirmed ones are skipped because that same rule keeps them
     * submitted whatever the call says, so all it could still change is the
     * submission time. One call per assignment, every assignment, every
     * refresh, was most of the requests a refresh made to Moodle by the
     * middle of a term. A pull to refresh still asks about everything.
     */
    private suspend fun fetchAssignmentsRound(courseIds: List<Int>): AssignmentsRound =
        withContext(Dispatchers.IO) {
            val recheck = recheckConfirmed.also { recheckConfirmed = false }
            val confirmed =
                if (recheck) emptyMap() else confirmedSubmissions(dataCache.loadAssignments())
            var askedWith = ""
            var asked = 0
            val round = attemptWithTokenRetry { token ->
                askedWith = token
                val userId = getSiteInfoUserId(token)
                val envelope = callGetAssignments(token, courseIds)
                val toAsk = envelope.courses.flatMap { it.assignments }
                    .filter { it.id !in confirmed }
                asked = toAsk.size
                val statuses = coroutineScope {
                    toAsk.map { a ->
                        async(Dispatchers.IO) {
                            runCatching { callGetSubmissionStatus(token, a.id, userId) }
                                .getOrNull()
                                ?.let { a.id to it }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
                AssignmentsRound(envelope, statuses, confirmed)
            }
            // Stamped here rather than at each caller, so every screen and
            // the worker date the data alike — see SchoolDataFreshness. Only
            // for a round that got an answer to every status call: a failed
            // one reads as "not submitted", and a stamp would keep the next
            // automatic fetch from correcting it. And only while the token
            // that asked is still the account's, so a round that outlived a
            // sign-out cannot vouch for whoever signs in next.
            if (round.statuses.size == asked && tokenService.currentToken() == askedWith) {
                prefs.markSchoolDataSynced(System.currentTimeMillis())
            }
            round
        }

    /** Run [block] with current token; on `invalidtoken`, refresh once and retry. */
    private suspend inline fun <T> attemptWithTokenRetry(block: (String) -> T): T {
        val token = tokenService.currentToken() ?: tokenService.refreshToken()
        return try {
            block(token)
        } catch (e: MoodleWebserviceError.InvalidToken) {
            Log.w("MoodleService", "wstoken rejected, refreshing once")
            tokenService.clearToken()
            synchronized(siteInfoLock) { cachedUserId = null }
            val fresh = tokenService.refreshToken()
            block(fresh)
        }
    }

    /**
     * Lazy site-info probe: serializes concurrent IO callers so only one
     * `core_webservice_get_site_info` round-trip fires per token, and
     * double-checks under the lock so a clear from [attemptWithTokenRetry]
     * doesn't get stomped on by an in-flight fetch.
     */
    private fun getSiteInfoUserId(token: String): Int {
        cachedUserId?.let { return it }
        synchronized(siteInfoLock) {
            cachedUserId?.let { return it }
            val url =
                "$webserviceUrl?moodlewsrestformat=json&wsfunction=core_webservice_get_site_info&wstoken=$token"
            val req = Request.Builder().url(url).post(FormBody.Builder().build()).build()
            val body = client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
                response.body.string()
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
    }

    private fun callEnrolledCourses(token: String, userId: Int): List<MoodleEnrolledCourse> {
        val url =
            "$webserviceUrl?moodlewsrestformat=json&wsfunction=core_enrol_get_users_courses&wstoken=$token"
        val form = FormBody.Builder().add("userid", userId.toString()).build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body.string()
        }
        MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
        return try {
            decodeEnrolledCourses(body)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("enrolled response not decodable: ${e.message}")
        }
    }

    private fun callGetAssignments(token: String, courseIds: List<Int>): MoodleAssignmentsEnvelope {
        val url =
            "$webserviceUrl?moodlewsrestformat=json&wsfunction=mod_assign_get_assignments&wstoken=$token"
        val form = FormBody.Builder().apply {
            courseIds.forEachIndexed { i, id -> add("courseids[$i]", id.toString()) }
        }.build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body.string()
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

    private fun callGetSubmissionStatus(
        token: String,
        assignId: Int,
        userId: Int
    ): MoodleSubmissionStatusEnvelope {
        val url =
            "$webserviceUrl?moodlewsrestformat=json&wsfunction=mod_assign_get_submission_status&wstoken=$token"
        val form = FormBody.Builder()
            .add("assignid", assignId.toString())
            .add("userid", userId.toString())
            .build()
        val req = Request.Builder().url(url).post(form).build()
        val body = client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw MoodleWebserviceError.HttpStatus(response.code)
            response.body.string()
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

    companion object {
        private val decodeGson = Gson()

        /**
         * The assignments [cached] records as submitted, by Moodle assignment
         * id, with when they were submitted.
         */
        internal fun confirmedSubmissions(cached: List<Assignment>): Map<Int, Date?> =
            cached.filter { it.isCompleted }
                .mapNotNull { a -> a.assignmentId.toIntOrNull()?.let { id -> id to a.submittedAt } }
                .toMap()

        /**
         * Whether assignment [id] is submitted, and when: as [confirmed]
         * recorded it when the cache already knew, else as Moodle's
         * [submission] says — null when its status call failed.
         */
        internal fun submissionState(
            id: Int,
            confirmed: Map<Int, Date?>,
            submission: MoodleSubmission?,
        ): Pair<Boolean, Date?> {
            if (id in confirmed) return true to confirmed[id]
            val submittedAt = submission?.timemodified?.takeIf { it > 0 }?.let { Date(it * 1000) }
            return (submission?.status == "submitted") to submittedAt
        }

        /**
         * Decodes a `core_enrol_get_users_courses` payload, dropping rows Gson
         * could not build.
         *
         * Split out from [callEnrolledCourses] so the case that actually
         * matters is testable offline, mirroring [SemesterCatalog.decodeSemesters].
         *
         * Gson does not strip nulls out of a JSON array: `[null, {...}]`
         * decodes to a list *holding* a null while Kotlin's type system insists
         * the element type is non-null, and the familiar `?: emptyList()` only
         * covers the whole list being null. Every downstream read in
         * [org.ntust.app.tigerduck.ui.screen.classtable.ClassTableViewModel]'s
         * `fetchData` — `it.idnumber`, `it.semesterCode`, `it.courseNo` — then
         * dereferences elements outside any try/catch, so one null row used to
         * reach the default uncaught handler and take down the process rather
         * than fail a single refresh.
         *
         * Gson decode failures propagate; the caller wraps them in
         * [MoodleWebserviceError.MalformedResponse].
         */
        internal fun decodeEnrolledCourses(json: String): List<MoodleEnrolledCourse> {
            val type = object : TypeToken<List<MoodleEnrolledCourse>>() {}.type
            val decoded: List<MoodleEnrolledCourse?> =
                decodeGson.fromJson(json, type) ?: emptyList()
            return decoded.filterNotNull()
        }
    }
}
