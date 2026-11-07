package org.ntust.app.tigerduck.push

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.ntust.app.tigerduck.data.CourseDeleteKind
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.EmptyBody
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.EditStamp
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.ConcurrentHashMap

/**
 * Hears each course upload the backend answered, whichever path sent it (a timetable fetch, a
 * term's roster, Use Local, the course adds), under the sign-in it went with: the outbox waits on
 * some of them ([org.ntust.app.tigerduck.data.CourseUploadNews]).
 */
fun interface CourseUploadListener {
    /** [sent], the course numbers of [semester] that went up, of which [answer] says what the backend took. */
    suspend fun landed(semester: String, sent: List<String>, answer: CourseUploadAnswer, signIn: SignIn)
}

/**
 * The course list's sync requests: a term's upload, one course's delete and a term's reset, with
 * the upload ledger of each term. For [PushApiClient], which keeps each as a method of its own.
 * Each is sent with [authHeader]'s header for its sign-in, to the endpoint [baseUrl] names at the
 * time; an upload through [uploads], with its deadline for the whole call. Each edit goes with its
 * age, read by [ages] as its request is built (`age_ms`), under its course's term ([FieldAges],
 * [SyncField.spanKey]): a course's adds, hides and removals ([SyncField.membership]) and a term's
 * reset ([SyncField.termReset]) go one at a time with every other course write of the term, and an
 * edit made while one was out ages from its end. A reset of every term names none.
 */
internal class CourseSyncRequests(
    private val api: BackendServices<PushApi>,
    private val uploads: BackendServices<PushApi>,
    private val prefs: AppPreferences,
    private val policies: SyncPolicies,
    private val baseUrl: () -> String,
    private val authHeader: suspend (SignIn) -> String,
    private val ages: FieldAges,
    // Told of each upload that landed; none for a client a test builds alone.
    private val news: dagger.Lazy<CourseUploadListener>? = null,
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
     * [forceKeys], the keys of the courses the caller adds now, goes whatever
     * the ledger holds, and so does one sent with [resend], for a term the
     * server has emptied since. Each upload defers the server's own Moodle
     * sync by two hours.
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
     * `patchAssignmentOverrideAnswered`'s is.
     *
     * [rosterTerms] names the terms the list is the school's whole roster
     * of; see [CourseUpload.of], which decides it. A roster the backend asks
     * for (`roster_requests`) goes with [resend], past the ledger.
     *
     * [addedAt], by course number, the time of each add [courses] carries (a
     * `PendingCourseDeletes.stamp`), sent as its age, which the backend applies only when later
     * than the course's last add, hide and reset (tigerduck-app#910). Every
     * other course of the upload is taken for the school's roster. A course
     * whose add the backend answered, applied or not, counts as accepted: the
     * backend has settled it. The answer tells the timed adds the backend
     * refused apart; see [CourseUploadAnswer].
     *
     * The upload carries no colors, and names every hand-added course in its adds, each stamped
     * with its [addedAt] entry or as the oldest; see [CourseUploadPayload.handAdds] (X9). A copy
     * merged down that no snapshot has told about goes only when added now
     * ([CourseUploadPayload.sendable]), and the ledger, the roster recorded and the listeners take
     * the courses that went; with every one left out, nothing is sent and the answer accepts none.
     */
    suspend fun uploadCoursesAnswered(
        courses: List<Course>,
        semester: String,
        signIn: SignIn,
        forceKeys: List<String> = emptyList(),
        resend: Boolean = false,
        rosterTerms: List<String> = emptyList(),
        addedAt: Map<String, EditTime> = emptyMap(),
    ): CourseUploadAnswer? = withContext(Dispatchers.IO) {
        if (!policies.current.uploadsCourses) return@withContext null
        // Resolved once, for the fingerprint and the request alike, so the
        // ledger remembers what this backend accepted; see uploadFingerprint.
        val base = baseUrl()
        val sent = CourseUploadPayload.sendable(courses, semester, forceKeys, addedAt)
        // Every course left out: nothing the backend would change.
        if (sent.isEmpty() && courses.isNotEmpty()) return@withContext CourseUploadAnswer(emptyList(), emptyList())
        // What the accepted list enrolled as the term's roster, for serverDropped.
        val roster = if (semester in rosterTerms) sent.map { it.courseNo } else null
        var accepted = CourseUploadAnswer(emptyList(), emptyList())
        var landed = false
        courseUploads(semester).sendIfDue(
            fingerprint = courseUploadFingerprint(base, sent, semester, rosterTerms),
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
            val adds = CourseUploadPayload.handAdds(sent, semester, forceKeys, addedAt)
            // A span when an add went with an age; the oldest's `hlc: 0` never lands after a later edit.
            val fields = adds.keys.map(SyncField::membership)
            accepted = ages.sendEach(fields, spans = adds.values.any { !it.isOldest }, signIn, { stampOf ->
                service.coursesUpload(header, body(sent, semester, rosterTerms, adds.mapValues { (_, madeAt) -> stampOf(madeAt) }))
            }, ::answered)
            landed = true
        }
        if (landed) tell(semester, sent, accepted, signIn)
        accepted
    }

    // An upload of [courses] that landed under [signIn], told to what waits on one ([news]): a
    // change that waited for its course's row, or one the upload left out. Its failing is not the
    // upload's, which the backend has.
    private suspend fun tell(semester: String, courses: List<Course>, answer: CourseUploadAnswer, signIn: SignIn) {
        val heard = news?.get() ?: return
        try {
            heard.landed(semester, courses.map { it.courseNo }, answer, signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a landed course upload was not told: ${e.logSummary()}")
        }
    }

    // The body of an upload of [courses] with their hand-adds' stamps [adds], by course key.
    private fun body(courses: List<Course>, semester: String, rosterTerms: List<String>, adds: Map<String, EditStamp>): RequestBody =
        gson.toJson(CourseUploadPayload.build(courses, semester, rosterTerms, adds)).toRequestBody(jsonType)

    // The courses an upload's answer took, and the timed adds it refused.
    private fun answered(response: Response): CourseUploadAnswer {
        if (!response.isSuccessful) {
            // The refusal keeps the backend's code, as every answered write's does (answerOf).
            throw BackendException(BackendError.of(response), "uploadCourses failed: HTTP ${response.code}")
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
        return CourseUploadAnswer(
            accepted = listed.filter { it.isNotEmpty() }.map(::no).distinct(),
            refusedAdds = answers.filter { !it.optBoolean("applied", true) }
                .map { it.optString("course_key") }.filter { it.isNotEmpty() }.map(::no).distinct(),
            serverClockUs = serverClockOf(response),
            // Only beside the keys it took, which tell the courses it left out from the rest.
            skippedTombstoned = json?.takeIf { keys != null }?.optInt(SKIPPED)?.takeIf { it > 0 },
        )
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
     * which could take that account's own course off. It names [kind], a hide or the removal of a
     * hand-add, as the iPhone's `deleteCourse` does; with none the backend decides from its row, and
     * hides a key it holds no row of (tigerduck-app-android#698).
     */
    suspend fun deleteCourse(courseKey: String, signIn: SignIn, hiddenAt: EditTime?, kind: CourseDeleteKind?): WriteAnswer =
        withContext(Dispatchers.IO) {
            if (!policies.current.uploadsCourses) return@withContext WriteAnswer.NOT_SENT
            val service = api.at(baseUrl())
            val header = authHeader(signIn)
            // With the hide's age, which the backend applies only when later than the course's last
            // add, hide and reset (tigerduck-app#910): a hide queued offline loses to an add made
            // since on another device, and a retry after a lost reply lands on about the same stamp.
            ages.send(
                listOf(SyncField.membership(courseKey)), hiddenAt, signIn,
                { service.removeCourse(header, courseKey, EmptyBody, it.ageMs, it.hlc, kind?.wire) },
            ) {
                answerOf(it, "deleteCourse", WriteAnswer.MEMBERSHIP)
            }
        }

    /**
     * Reset the user's courses on the backend.
     *
     * [semester] scopes the reset to one term. The backend leaves
     * `courses_reset_at` alone for a scoped reset, so other devices do not
     * wipe every term's local overlay — an unscoped reset still stamps it and
     * still means "wipe everything". Reset is offered per term because the
     * timetable is now per term; see `DELETE /sync/courses` in the backend's
     * `routes/sync/courses.py`. It carries the stamp of [resetAt], the time it
     * was asked ([FieldAges.send]), 0 old for one asked now (null).
     */
    suspend fun deleteAllCourses(semester: String?, signIn: SignIn, resetAt: EditTime?): WriteAnswer =
        withContext(Dispatchers.IO) {
            if (!policies.current.uploadsCourses) return@withContext WriteAnswer.NOT_SENT
            val service = api.at(baseUrl())
            val header = authHeader(signIn)
            val term = semester?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            val reset: suspend () -> WriteAnswer = {
                val fields = listOfNotNull(semester?.let(SyncField::termReset))
                ages.send(fields, resetAt, signIn, { service.removeCourses(header, term, EmptyBody, it.ageMs, it.hlc) }) {
                    answerOf(it, "deleteAllCourses", WriteAnswer.RESET)
                }
                    // The upload after the reset, usually the list sent before it,
                    // releases the reset's tombstones: it has to go.
                    .also { prefs.forgetCourseUploads(semester) }
            }
            // Under the term's upload ledger, so no upload is out across the
            // reset; see UploadLedger.holdingUploads. Nothing resets every term
            // today, and that path holds no ledger.
            if (semester != null) courseUploads(semester).holdingUploads(reset) else reset()
        }

    private companion object {
        // How many of the courses sent an upload's answer left out as tombstoned.
        const val SKIPPED = "skipped_tombstoned"

        const val TAG = "CourseSyncRequests"
    }
}

/**
 * What a course upload's answer says: the course numbers the backend took ([accepted]), and the
 * timed adds it answered but did not apply ([refusedAdds]), as a hide or reset made after them
 * elsewhere is newer. Both are settled: the backend has the final word on each. [serverClockUs] is
 * the answer's `X-TigerSync-Clock`, null for an upload the ledger held back or an answer without one.
 * [skippedTombstoned] is how many of the courses sent it left out as tombstoned, made no row for:
 * null for none, and when the answer does not say or names no keys it took to tell which.
 */
data class CourseUploadAnswer(
    val accepted: List<String>,
    val refusedAdds: List<String>,
    val serverClockUs: Long? = null,
    val skippedTombstoned: Int? = null,
) {
    val settled: List<String> get() = (accepted + refusedAdds).distinct()

    /** The course numbers of [sent] the backend left out as tombstoned: those it did not take, when it says it left any out. */
    fun skippedOf(sent: Collection<String>): List<String> =
        if ((skippedTombstoned ?: 0) > 0) sent.filter { it !in accepted }.distinct() else emptyList()
}
