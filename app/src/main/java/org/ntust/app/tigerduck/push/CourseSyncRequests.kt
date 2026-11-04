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
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.BackendServices
import org.ntust.app.tigerduck.network.EmptyBody
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
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
 * time; an upload through [uploads], with its deadline for the whole call.
 */
internal class CourseSyncRequests(
    private val api: BackendServices<PushApi>,
    private val uploads: BackendServices<PushApi>,
    private val prefs: AppPreferences,
    private val policies: SyncPolicies,
    private val baseUrl: () -> String,
    private val authHeader: suspend (SignIn) -> String,
    private val clock: WriteClock,
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
     *
     * On `/v4` the upload carries no colors and no [forceKeys], and names every hand-added
     * course in its adds, each stamped with its [addedAt] entry or as the oldest; see
     * [CourseUploadPayload.handAdds] (X9). A copy merged down that no snapshot has told about goes
     * only when added now ([CourseUploadPayload.sendable]), and the ledger, the roster recorded and
     * the listeners take the courses that went; with every one left out, nothing is sent and the
     * answer accepts none. One refused as ahead goes once more, its timed adds
     * stamped again from the refusal's clock, never without them, if course sync is still on:
     * switched off meanwhile, nothing went and the answer is null.
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
        // Resolved once, for the fingerprint and the request alike, so the
        // ledger remembers what this backend accepted; see uploadFingerprint.
        val base = baseUrl()
        val api = BackendApi.of(base)
        val sent = if (api == BackendApi.V4) CourseUploadPayload.sendable(courses, semester, forceKeys, addedAt) else courses
        // Every course left out: nothing the backend would change.
        if (sent.isEmpty() && courses.isNotEmpty()) return@withContext CourseUploadAnswer(emptyList(), emptyList())
        val bodies = when (api) {
            BackendApi.V3 -> v3Upload(sent, semester, forceKeys, rosterTerms, addedAt)
            BackendApi.V4 -> v4Upload(sent, semester, forceKeys, rosterTerms, addedAt)
        }
        // What the accepted list enrolled as the term's roster, for serverDropped.
        val roster = if (semester in rosterTerms) sent.map { it.courseNo } else null
        var accepted = CourseUploadAnswer(emptyList(), emptyList())
        var landed = false
        try {
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
                var response = service.coursesUpload(header, bodies.first, bodies.protocol).execute()
                val again = bodies.again
                if (again != null && isClockAhead(response)) {
                    val serverClockUs = serverClockOf(response)
                    response.close()
                    // On /v4, past the gate the first request passed, read again: course sync can have
                    // gone off meanwhile. Nothing then went, and the ledger records nothing.
                    if (api == BackendApi.V4 && !sends()) throw ResendHeldBack()
                    response = service.coursesUpload(header, again(serverClockUs), null).execute()
                }
                accepted = response.use { answered(it, api) }
                landed = true
            }
        } catch (e: ResendHeldBack) {
            return@withContext null
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

    // Whether the course list's writes go: the gate each checks first, and on /v4 again before a resend.
    private fun sends(): Boolean = policies.current.uploadsCourses

    // An upload refused as ahead whose resend the gate stopped: not sent.
    private class ResendHeldBack : Exception()

    /** An upload's body, the protocol it says it speaks, and the body it goes again with once refused as ahead. */
    private class UploadBodies(val first: RequestBody, val protocol: String?, val again: ((serverClockUs: Long?) -> RequestBody)?)

    // /v3's: the courses with their custom colors and force keys. With timed adds it goes under
    // protocol 2, and once refused as ahead of the backend's clock, again without them.
    private fun v3Upload(
        courses: List<Course>, semester: String, forceKeys: List<String>, rosterTerms: List<String>, addedAt: Map<String, Long>,
    ): UploadBodies {
        val payload = CourseUploadPayload.build(courses, semester, forceKeys, rosterTerms)
        val body = gson.toJson(payload).toRequestBody(jsonType)
        if (addedAt.isEmpty()) return UploadBodies(body, protocol = null, again = null)
        val adds = addedAt.map { (courseNo, at) ->
            // The key an add forces past a tombstone (PendingCourseAdds.forceKey).
            mapOf("course_key" to CourseUploadPayload.key(semester, courseNo), "hlc" to clockOf(at))
        }
        return UploadBodies(gson.toJson(payload + ("adds" to adds)).toRequestBody(jsonType), PROTOCOL_STAMPED) { body }
    }

    // /v4's: the courses and their hand-adds. Once refused as ahead, each add stamped later than
    // the clock now allows goes again at that, and the oldest stay the oldest.
    private fun v4Upload(
        courses: List<Course>, semester: String, forceKeys: List<String>, rosterTerms: List<String>, addedAt: Map<String, Long>,
    ): UploadBodies {
        val adds = CourseUploadPayload.handAdds(courses, semester, forceKeys, addedAt)
        fun body(stamps: Map<String, Long>) = gson.toJson(CourseUploadPayload.buildV4(courses, semester, rosterTerms, stamps)).toRequestBody(jsonType)
        val again = { serverClockUs: Long? ->
            val restamp = clock.restampAfterClockAhead(serverClockUs)
            body(adds.mapValues { (_, atMs) -> minOf(atMs, restamp) })
        }
        return UploadBodies(body(adds), protocol = null, again = again.takeIf { adds.values.any { it > 0 } })
    }

    // The courses an upload's answer took, and the timed adds it refused; on /v4, its stamps heard.
    private fun answered(response: Response, api: BackendApi): CourseUploadAnswer {
        if (!response.isSuccessful) {
            val what = "uploadCourses failed: HTTP ${response.code}"
            // /v4's refusal keeps the backend's code, as every answered write's does (answerOf), so
            // the course adds can tell a second clock_ahead from a refusal; /v3's is as it was.
            if (api == BackendApi.V4) throw BackendException(BackendError.of(response), what)
            throw PushApiException(what, response.code)
        }
        // A 2xx with an unreadable body (a captive portal, a proxy) is
        // still an upload that landed; it just cannot say what was accepted.
        val json = runCatching { JSONObject(response.body.string()) }.getOrNull()
        val keys = json?.optJSONArray("accepted_keys")
        val answers = json?.optJSONArray("adds")?.let { (0 until it.length()).mapNotNull(it::optJSONObject) }.orEmpty()
        if (api == BackendApi.V4) answers.mapNotNull { nullableLong(it, "hlc") }.maxOrNull()?.let(clock::hear)
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
     * which could take that account's own course off. With no kind, on either version: the
     * backend decides from its row, a hide of a school course and the removal of one added by
     * hand, as the iPhone's `deleteCourse` sends it.
     */
    suspend fun deleteCourse(courseKey: String, signIn: SignIn, hiddenAt: Long?): WriteAnswer =
        withContext(Dispatchers.IO) {
            if (!policies.current.uploadsCourses) return@withContext WriteAnswer.NOT_SENT
            val base = baseUrl()
            val service = api.at(base)
            val header = authHeader(signIn)
            // With the hide's time, which the backend applies only when later than the course's last
            // add, hide and reset (tigerduck-app#910): a hide queued offline loses to an add made
            // since on another device, and a retry after a lost reply changes nothing. On /v3 one
            // refused as ahead of the backend's clock goes again without it, counting from when it
            // arrives; on /v4, where it always goes, stamped again from the refusal's clock.
            when (val version = BackendApi.of(base)) {
                BackendApi.V3 -> executeStamped(hiddenAt, { true }, { hlc -> service.removeCourse(header, courseKey, EmptyBody, hlc) }) {
                    clock.answer(it, version, "deleteCourse", WriteAnswer.MEMBERSHIP)
                }
                // The resend past the gate the first request passed, read again: course sync can have gone off meanwhile.
                BackendApi.V4 -> executeStampedV4(hiddenAt, clock, ::sends, { hlc -> service.removeCourseStamped(header, courseKey, EmptyBody, hlc) }) {
                    clock.answer(it, version, "deleteCourse", WriteAnswer.MEMBERSHIP)
                }
            } ?: WriteAnswer.NOT_SENT
        }

    /**
     * Reset the user's courses on the backend.
     *
     * [semester] scopes the reset to one term. The backend leaves
     * `courses_reset_at` alone for a scoped reset, so other devices do not
     * wipe every term's local overlay — an unscoped reset still stamps it and
     * still means "wipe everything". Reset is offered per term because the
     * timetable is now per term; see `DELETE /sync/courses` in the backend's
     * `routes/sync/courses.py`. On `/v4` it carries [resetAtMs], the time it
     * was asked, or with none the clock's stamp at its send, and is stamped
     * again once refused as ahead; `/v3` takes none.
     */
    suspend fun deleteAllCourses(semester: String?, signIn: SignIn, resetAtMs: Long?): WriteAnswer =
        withContext(Dispatchers.IO) {
            if (!policies.current.uploadsCourses) return@withContext WriteAnswer.NOT_SENT
            val base = baseUrl()
            val version = BackendApi.of(base)
            val service = api.at(base)
            val header = authHeader(signIn)
            val term = semester?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            val reset: suspend () -> WriteAnswer = {
                when (version) {
                    BackendApi.V3 -> service.removeCourses(header, term, EmptyBody).execute().use {
                        clock.answer(it, version, "deleteAllCourses", WriteAnswer.RESET)
                    }
                    BackendApi.V4 -> executeStampedV4(resetAtMs, clock, ::sends, { hlc -> service.removeCoursesStamped(header, term, EmptyBody, hlc) }) {
                        clock.answer(it, version, "deleteAllCourses", WriteAnswer.RESET)
                    } ?: WriteAnswer.NOT_SENT
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
        // The header value of an upload whose adds carry their time.
        const val PROTOCOL_STAMPED = "2"

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
