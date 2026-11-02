package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.EmptyBody
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncPolicies
import java.util.concurrent.ConcurrentHashMap

/**
 * The course list's sync requests: a term's upload, one course's delete and a term's reset, with
 * the upload ledger of each term. For [PushApiClient], which keeps each as a method of its own.
 * Each is sent with [authHeader]'s header for its sign-in, to the endpoint [baseUrl] names at the
 * time; an upload through [uploads], with its deadline for the whole call.
 */
internal class CourseSyncRequests(
    private val api: BackendServices<PushApi>,
    private val uploads: BackendServices<PushApi>,
    private val prefs: AppPreferences,
    private val policies: SyncPolicies,
    private val baseUrl: () -> String,
    private val authHeader: suspend (SignIn) -> String,
) {
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    // One ledger per term, over the term's own slot in AppPreferences; see
    // AppPreferences.coursesUploadFingerprint for why not one slot for all.
    private val courseUploads = ConcurrentHashMap<String, UploadLedger>()

    private fun courseUploads(semester: String): UploadLedger = courseUploads.getOrPut(semester) {
        UploadLedger(
            store = object : UploadLedgerStore {
                override val fingerprint: String? get() = prefs.coursesUploadFingerprint(semester)
                override val acceptedAtMs: Long get() = prefs.coursesUploadedAtMs(semester)
            },
            clock = System::currentTimeMillis,
        )
    }

    /**
     * Upload the user's course list so the backend can persist it for
     * cross-device sync. Callers wrap this in `runCatching` — a failure here
     * must never block the normal fetch/save flow.
     *
     * A term's list the backend already accepted goes again only once it
     * changes, or six hours on ([UploadLedger]), as assignments do; one with
     * [forceKeys] goes whatever the ledger holds, and so does one sent with
     * [resend], for a term the server has emptied since. Each upload defers
     * the server's own Moodle sync by two hours.
     *
     * Returns the course numbers the server actually accepted, or null when
     * nothing was sent (sync off). A 200 is not acceptance: the server skips
     * any course another device has tombstoned and says so in the body, and
     * a caller that took the status for "the server has seen these" would
     * retire a hand-typed course's protection on an upload that was refused.
     * A list skipped as unchanged answers with none: the server accepted
     * them lately, and said so then.
     *
     * [signIn] pins the request, and the ledger's record, to that sign-in, as
     * `patchAssignmentOverride`'s is.
     *
     * [rosterTerms] names the terms the list is the school's whole roster
     * of; see [CourseUpload.of], which decides it. A roster the backend asks
     * for (`roster_requests`) goes with [resend], past the ledger.
     *
     * [addedAt], by course number, the time of each add [courses] carries (a
     * `PendingCourseDeletes.stamp`): sent under protocol 2 as `adds`, which the
     * backend applies only when later than the course's last add, hide and
     * reset (tigerduck-app#910). Under protocol 2 every other course of the
     * upload is taken for the school's roster, so such an upload holds the
     * added courses alone. A course whose add the backend answered, applied or
     * not, counts as accepted: the backend has settled it. One it refuses as
     * ahead of its clock goes again unstamped, counting from when it arrives.
     * The answer tells the timed adds the backend refused apart; see [CourseUploadAnswer].
     */
    suspend fun uploadCoursesAnswered(
        courses: List<Course>,
        semester: String,
        signIn: SignIn,
        forceKeys: List<String> = emptyList(),
        resend: Boolean = false,
        rosterTerms: List<String> = emptyList(),
        addedAt: Map<String, Long> = emptyMap(),
    ): CourseUploadAnswer? = withContext(Dispatchers.IO) {
        if (!policies.current.uploadsCourses) return@withContext null
        val payload = CourseUploadPayload.build(courses, semester, forceKeys, rosterTerms)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        val stampedBody = addedAt.takeIf { it.isNotEmpty() }?.let { stamps ->
            val adds = stamps.map { (courseNo, at) ->
                // The key an add forces past a tombstone (PendingCourseAdds.forceKey).
                mapOf("course_key" to "client:$semester:$courseNo", "hlc" to clockOf(at))
            }
            gson.toJson(payload + ("adds" to adds)).toRequestBody(jsonType)
        }
        // Resolved once, for the fingerprint and the request alike, so the
        // ledger remembers what this backend accepted; see uploadFingerprint.
        val base = baseUrl()
        // What the accepted list enrolled as the term's roster, for serverDropped.
        val roster = if (semester in rosterTerms) courses.map { it.courseNo } else null
        var accepted = CourseUploadAnswer(emptyList(), emptyList())
        courseUploads(semester).sendIfDue(
            fingerprint = courseUploadFingerprint(base, courses, semester, rosterTerms),
            // The pair in one step, so a sign-out's clear cannot fall between
            // the check and the record; see UploadLedger.sendIfDue.
            record = { fingerprint, atMs ->
                signIn.ifCurrent {
                    prefs.recordCoursesUpload(semester, fingerprint, atMs)
                    prefs.recordCoursesUploadRoster(semester, roster)
                }
            },
            always = resend || forceKeys.isNotEmpty(),
            signIn = signIn,
        ) {
            // Through uploadCall, with its deadline for the whole call.
            val service = uploads.at(base)
            val header = authHeader(signIn)
            fun send(stamped: Boolean): CourseUploadAnswer? {
                val call = if (stamped) service.coursesUpload(header, stampedBody!!, PROTOCOL_STAMPED)
                    else service.coursesUpload(header, body, null)
                return call.execute().use { response ->
                    if (stamped && isClockAhead(response)) return@use null
                    if (!response.isSuccessful) {
                        throw PushApiException("uploadCourses failed: HTTP ${response.code}", response.code)
                    }
                    // A 2xx with an unreadable body (a captive portal, a proxy) is
                    // still an upload that landed; it just cannot say what was accepted.
                    val json = runCatching { JSONObject(response.body.string()) }.getOrNull()
                    val keys = json?.optJSONArray("accepted_keys")
                    val answers = json?.optJSONArray("adds")?.let { (0 until it.length()).mapNotNull(it::optJSONObject) }.orEmpty()
                    // "client:{semester}:{course_no}" — the shape CourseUploadPayload sends.
                    fun no(key: String) = key.substringAfterLast(':')
                    val listed = keys?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty() +
                        answers.filter { it.optBoolean("applied") }.map { it.optString("course_key") }
                    CourseUploadAnswer(
                        accepted = listed.filter { it.isNotEmpty() }.map(::no).distinct(),
                        refusedAdds = answers.filter { !it.optBoolean("applied", true) }
                            .map { it.optString("course_key") }.filter { it.isNotEmpty() }.map(::no).distinct(),
                    )
                }
            }
            accepted = (if (stampedBody != null) send(stamped = true) else null) ?: send(stamped = false)!!
        }
        accepted
    }

    /**
     * A full sync found [courseNos] of [semester] dropped on the server, by another device's
     * roster. When the term's list the backend last accepted from here went as its roster and
     * listed one of them, the server no longer holds what it accepted: the ledger forgets it, and
     * the next roster goes, unchanged or not. Otherwise a course selection answer listing the
     * course again waited six hours, the course kept here and dropped on the server, without its
     * reminders. Only a roster's: an upload without one leaves a dropped row dropped, and
     * forgetting it would send it at every fetch to no effect.
     */
    suspend fun serverDropped(semester: String, courseNos: Collection<String>) {
        fun listsOne() = prefs.coursesUploadRoster(semester)?.any { it in courseNos } == true
        if (!listsOne()) return
        // Under the ledger: an upload out now would record its list after the forget.
        courseUploads(semester).holdingUploads { if (listsOne()) prefs.forgetCourseUploads(semester) }
    }

    /**
     * Deletes the course row [courseKey] names. [signIn] pins the request to
     * that sign-in, as [uploadCoursesAnswered] does: a delete queued as one account and sent after a sign-out and
     * another sign-in goes without a token, not with the next account's,
     * which could take that account's own course off.
     */
    suspend fun deleteCourse(courseKey: String, signIn: SignIn, hiddenAt: Long? = null) = withContext(Dispatchers.IO) {
        if (!policies.current.uploadsCourses) return@withContext
        val service = api.at(baseUrl())
        val header = authHeader(signIn)
        // With the hide's time, which the backend applies only when later than the course's last
        // add, hide and reset (tigerduck-app#910): a hide queued offline loses to an add made
        // since on another device, and a retry after a lost reply changes nothing. One refused as
        // ahead of the backend's clock goes again without it, counting from when it arrives.
        fun send(hlc: Long?): Int? = service.removeCourse(header, courseKey, EmptyBody, hlc).execute().use { response ->
            when {
                response.isSuccessful -> null
                hlc != null && isClockAhead(response) -> CLOCK_AHEAD
                else -> response.code
            }
        }
        var failed = send(hiddenAt?.let(::clockOf))
        if (failed == CLOCK_AHEAD) failed = send(null)
        if (failed != null) throw PushApiException("deleteCourse failed: HTTP $failed", failed)
    }

    /**
     * Reset the user's courses on the backend.
     *
     * [semester] scopes the reset to one term. The backend leaves
     * `courses_reset_at` alone for a scoped reset, so other devices do not
     * wipe every term's local overlay — an unscoped reset still stamps it and
     * still means "wipe everything". Reset is offered per term because the
     * timetable is now per term; see `DELETE /sync/courses` in the backend's
     * `routes/sync/courses.py`.
     */
    suspend fun deleteAllCourses(semester: String?, signIn: SignIn) = withContext(Dispatchers.IO) {
        if (!policies.current.uploadsCourses) return@withContext
        val service = api.at(baseUrl())
        val call = service.removeCourses(
            authHeader(signIn),
            semester?.let { java.net.URLEncoder.encode(it, "UTF-8") },
            EmptyBody,
        )
        val reset: suspend () -> Unit = {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw PushApiException("deleteAllCourses failed: HTTP ${response.code}")
                }
            }
            // The upload after the reset, usually the list sent before it,
            // releases the reset's tombstones: it has to go.
            prefs.forgetCourseUploads(semester)
        }
        // Under the term's upload ledger, so no upload is out across the
        // reset; see UploadLedger.holdingUploads. Nothing resets every term
        // today, and that path holds no ledger.
        if (semester != null) courseUploads(semester).holdingUploads(reset) else reset()
    }

    private companion object {
        // The header value of an upload whose adds carry their time.
        const val PROTOCOL_STAMPED = "2"

        // [deleteCourse]'s mark for a stamped delete the backend refused as ahead of its clock.
        const val CLOCK_AHEAD = -1
    }
}

/**
 * What a course upload's answer says: the course numbers the backend took ([accepted]), and the
 * timed adds it answered but did not apply ([refusedAdds]), as a hide or reset made after them
 * elsewhere is newer. Both are settled: the backend has the final word on each.
 */
data class CourseUploadAnswer(val accepted: List<String>, val refusedAdds: List<String>) {
    val settled: List<String> get() = (accepted + refusedAdds).distinct()
}
