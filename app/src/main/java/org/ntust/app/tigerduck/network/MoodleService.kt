package org.ntust.app.tigerduck.network

import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusLedger
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusWriter
import org.ntust.app.tigerduck.data.moodle.SubmissionStatusAnswer
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.model.MoodleAssignmentsCourse
import org.ntust.app.tigerduck.network.model.MoodleAssignmentsEnvelope
import org.ntust.app.tigerduck.network.model.MoodleAssignmentNode
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.util.MAX_CONCURRENT_SCHOOL_REQUESTS
import org.ntust.app.tigerduck.util.SharedFetch
import org.ntust.app.tigerduck.util.mapAtMost
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoodleService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val tokenService: MoodleTokenService,
    private val courseService: CourseService,
    private val dataCache: DataCache,
    private val prefs: AppPreferences,
    private val webservice: MoodleWebservice,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val client: OkHttpClient get() = sessionManager.client

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

    /**
     * A round's assignments for the caller to write with [storeAssignments], which orders the
     * write by when the round's own request for each status went out, so a page's answer asked
     * for after it is not taken back.
     */
    class AssignmentsAnswer internal constructor(
        val assignments: List<Assignment>,
        /**
         * What Moodle answered for each status the round asked and got, by assignment id: the
         * only statuses the write accepts as the round's, by their own request times. One kept
         * from the cache, confirmed or whose request failed, is not among them.
         */
        internal val statuses: Map<String, SubmissionStatusAnswer>,
        /** The cache's generation the round ran under. */
        internal val generation: Int,
    )

    /** The network half of [fetchAssignments]: what every caller asking for the same courses can share. */
    private class AssignmentsRound(
        val courses: List<MoodleAssignmentsCourse>,
        val statuses: Map<Int, MoodleSubmissionStatusEnvelope>,
        /** Submissions the cache had confirmed, not asked about — see [confirmedSubmissions]. */
        val confirmed: Map<Int, Date>,
        /**
         * When each status request went out, by assignment id, on the monotonic clock the
         * [AssignmentStatusLedger] orders answers by: a round asks a few at a time, and one time
         * for all would let a page's answer asked for in between pass for newer than one asked after it.
         */
        val askedAtMs: Map<Int, Long>,
        /** The cache's generation the round ran under; see [DataCache.userDataGeneration]. */
        val generation: Int,
    )

    // Refreshes in progress that every assignment round starting meanwhile
    // asks Moodle about every submission for — see recheckingSubmissions.
    private val recheckingRefreshes = AtomicInteger()

    /**
     * Drops the answers kept for sharing, so the next fetch asks Moodle
     * again. For a refresh the user pulled for: they may have just submitted
     * something, and an answer from a minute ago would show it outstanding.
     */
    fun expireSharedResults() {
        sharedEnrolled.expire()
        sharedAssignments.expire()
    }

    /**
     * Runs [block], a refresh the user pulled for or the server asked for,
     * with every assignment round that starts meanwhile asking Moodle about
     * every submission, the confirmed ones included: one may just have been
     * submitted again. For as long as the refresh runs, not for the next
     * round only: rounds are per course list, and the calendar's or the
     * worker's, on the cached roster, used to take a flag meant for the
     * pull's own round, which then skipped them.
     */
    suspend fun <T> recheckingSubmissions(block: suspend () -> T): T {
        recheckingRefreshes.incrementAndGet()
        try {
            return block()
        } finally {
            recheckingRefreshes.decrementAndGet()
        }
    }

    /**
     * Stops the fetches still running for the account that is signing out,
     * and drops their answers. They run on the application scope, so the
     * screens that asked being cancelled does not stop them.
     */
    fun cancelSharedFetches() {
        sharedEnrolled.cancelAll()
        sharedAssignments.cancelAll()
        statusLedger.clear()
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
                // The site info is asked under the token, so a stale token there is renewed too.
                webservice.withToken { token ->
                    val userId = webservice.siteInfo(token).userId
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
     * parallel) to derive `isCompleted` — see [submissionState].
     *
     * The caller passes in the already-fetched Moodle enrolment list so we
     * don't duplicate `core_enrol_get_users_courses` when the ViewModel has
     * just called `fetchEnrolledCourses` to build the course schedule.
     * Mirrors iOS: filter to the current in-app roster when available (so
     * dropped courses are excluded), else fall back to semesterCode filtering
     * on first launch when the roster cache is still empty.
     *
     * Null when no course was left to ask about, which the callers answer
     * by keeping the assignments they have, as iOS does. Any
     * list is Moodle's answer, and an empty one replaces them like any
     * other: see [requestAssignments], which throws for everything else.
     */
    suspend fun fetchAssignments(
        enrolledCourses: List<MoodleEnrolledCourse>,
        rosterCourseNos: Set<String>? = null,
    ): AssignmentsAnswer? = withContext(Dispatchers.IO) {
        val token = tokenService.currentToken()
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
        if (relevant.isEmpty()) {
            // Moodle answered, and has nothing for this term: the weeks before
            // its courses open, or a break between terms. Dated like any other
            // answer, or the worker would ask again on every run and the
            // "Last synced" row would never move. No courses at all is not
            // dated: it is also what an upstream failing quietly looks like,
            // and what the class table passes when its enrolment fetch fails.
            if (enrolledCourses.isNotEmpty()) markSynced(askedWith = token)
            return@withContext null
        }
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
        // An extension found before: kept for an assignment this round did not ask about.
        val cachedExtensions = dataCache.loadAssignments()
            .mapNotNull { a -> a.extensionDueDate?.let { a.assignmentId to it } }
            .toMap()

        // Flatten the nested course→assignments response.
        val assignments = round.courses
            .flatMap { c -> c.assignments.map { a -> c.id to a } }
            .mapNotNull { (courseId, a) ->
                if (a.duedate <= 0) return@mapNotNull null
                // Info-only entries with no submission target — nothing to
                // complete or ignore; skip to match iOS.
                if (a.nosubmissions != 0) return@mapNotNull null
                val course = coursesById[courseId]
                val (submitted, submittedAt) = submissionState(
                    a.id, round.confirmed, round.statuses[a.id]?.lastattempt,
                )
                Assignment(
                    assignmentId = a.id.toString(),
                    courseNo = courseNoById[courseId] ?: "",
                    courseName = courseName(course?.fullname).decodeHtmlEntities(),
                    title = a.name.decodeHtmlEntities(),
                    dueDate = Date(a.duedate * 1000),
                    isCompleted = submitted,
                    moodleUrl = "https://moodle2.ntust.edu.tw/mod/assign/view.php?id=${a.cmid}",
                    cutoffDate = a.cutoffdate?.takeIf { it > 0 }?.let { Date(it * 1000) },
                    submittedAt = submittedAt,
                    semester = currentSemester,
                    extensionDueDate = extensionDueDate(
                        a.id, a.id in round.statuses, round.statuses[a.id]?.lastattempt, cachedExtensions,
                    ),
                )
            }
        val statuses = round.statuses.mapNotNull { (id, status) ->
            val askedAt = round.askedAtMs[id] ?: return@mapNotNull null
            val (submitted, submittedAt) = submissionState(id, emptyMap(), status.lastattempt)
            SubmissionStatusAnswer(
                id.toString(), submitted, submittedAt?.time, askedAt, round.generation,
                extensionDueMs = extensionDueDate(id, asked = true, status.lastattempt, emptyMap())?.time,
            )
        }.associateBy { it.assignmentId }
        AssignmentsAnswer(assignments, statuses, round.generation)
    }

    /**
     * `mod_assign_get_assignments` for [courseIds], then one
     * `mod_assign_get_submission_status` per assignment the cache has not
     * already confirmed and that can still change ([assignmentsToAsk]), four
     * at a time. A status call that fails is
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
            val generation = dataCache.userDataGeneration
            val recheck = recheckingRefreshes.get() > 0
            val confirmed =
                if (recheck) emptyMap() else confirmedSubmissions(dataCache.loadAssignments())
            val closedBefore = dataCache.loadClosedAssignments()
            val closed = if (recheck) emptyMap() else closedBefore
            var askedWith = ""
            var asked = 0
            var askedAt = 0L
            var closedNow: Map<Int, Long> = emptyMap()
            val round = webservice.withToken { token ->
                askedWith = token
                val userId = webservice.siteInfo(token).userId
                val courses = callGetAssignments(token, courseIds)
                val all = courses.flatMap { it.assignments }
                val toAsk = assignmentsToAsk(all, confirmed, closed, System.currentTimeMillis())
                asked = toAsk.size
                askedAt = System.currentTimeMillis()
                val answers = mapAtMost(MAX_CONCURRENT_SCHOOL_REQUESTS, toAsk) { a ->
                    val sentAt = SystemClock.elapsedRealtime()
                    runCatching { callGetSubmissionStatus(token, a.id, userId) }
                        .getOrNull()
                        ?.let { Triple(a.id, it, sentAt) }
                }.filterNotNull()
                val statuses = answers.associate { (id, status, _) -> id to status }
                closedNow = closedUnsubmitted(all, statuses, askedAt, closedBefore)
                AssignmentsRound(courses, statuses, confirmed, answers.associate { (id, _, sentAt) -> id to sentAt }, generation)
            }
            // Only for the account that asked, as the stamp below.
            if (tokenService.currentToken() == askedWith) {
                if (closedNow != closedBefore) dataCache.saveClosedAssignments(closedNow)
                // The assignments' pages and the courses' lists read these; the generation check
                // inside keeps a round that outlived a sign-out from writing the next account's.
                dataCache.saveAssignmentRecords(assignmentRecords(round.courses), generation)
            }
            // Stamped here rather than at each caller, so every screen and
            // the worker date the data alike — see RefreshPolicies. Only
            // for a round that got an answer to every status call: a failed
            // one reads as "not submitted", and a stamp would keep the next
            // automatic fetch from correcting it.
            if (round.statuses.size == asked) markSynced(askedWith)
            round
        }

    /**
     * Dates the school data as of now, if the wstoken that asked is still
     * the account's: an answer that outlived a sign-out cannot vouch for
     * whoever signs in next.
     */
    private fun markSynced(askedWith: String?) {
        if (askedWith != null && tokenService.currentToken() == askedWith) {
            prefs.markSchoolDataSynced(System.currentTimeMillis())
        }
    }

    private fun callEnrolledCourses(token: String, userId: Int): List<MoodleEnrolledCourse> {
        val req = MoodleWebservice.request("core_enrol_get_users_courses", token, listOf("userid" to userId.toString()))
        val body = MoodleWebservice.execute(client, req)
        return try {
            decodeEnrolledCourses(body)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("enrolled response not decodable: ${e.message}")
        }
    }

    private fun callGetAssignments(token: String, courseIds: List<Int>): List<MoodleAssignmentsCourse> =
        requestAssignments(client, token, courseIds)

    private fun callGetSubmissionStatus(
        token: String,
        assignId: Int,
        userId: Int,
    ): MoodleSubmissionStatusEnvelope =
        requestSubmissionStatus(client, token, assignId, userId)

    /**
     * The order of the status answers the cached assignments take, a round's and the pages';
     * see [storeAssignments] and [AssignmentStatusWriter].
     */
    val statusLedger = AssignmentStatusLedger { dataCache.userDataGeneration }

    /**
     * Writes [assignments], a round's [answer] as the caller merged it, to the cache, with each
     * status reconciled against the newest answer accepted for the assignment by the round's own
     * request time, so an older round cannot take back a page's or a later round's status. Under
     * the cache's lock, so a page's write cannot pass it.
     *
     * The list as written, which is what the caller shows, uploads and arms reminders from: the
     * reconciliation can change what the caller merged. Null, and nothing written, once the data
     * was wiped under the round, when the caller publishes nothing.
     */
    suspend fun storeAssignments(assignments: List<Assignment>, answer: AssignmentsAnswer): List<Assignment>? =
        storeAssignments(dataCache, statusLedger, assignments, answer)

    companion object {
        private val decodeGson = Gson()

        /**
         * Moodle course fullname is typically "1142 PE139B022 課程名稱 / English".
         * Strip the semester + course-number prefix to recover the Chinese name,
         * the course number read by [CourseService.COURSE_NO], so a course taken
         * at NTU or NTNU loses its `3N…` too. Mirrors the iOS `courseName(from:)`
         * helper.
         */
        internal fun courseName(fullname: String?): String {
            if (fullname.isNullOrBlank()) return ""
            val parts = fullname.split(" ")
            val idx = parts.indexOfFirst { CourseService.COURSE_NO.containsMatchIn(it) }
            return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else fullname
        }

        /** The write behind [MoodleService.storeAssignments], on the given cache and ledger. */
        internal suspend fun storeAssignments(
            dataCache: DataCache,
            ledger: AssignmentStatusLedger,
            assignments: List<Assignment>,
            answer: AssignmentsAnswer,
        ): List<Assignment>? =
            dataCache.updateAssignments(answer.generation) { ledger.reconcile(assignments, answer.statuses, answer.generation) }

        /**
         * What each round keeps of every assignment of the answer, the undated and the ones
         * taking no submission included, for the assignments' pages and the courses' lists.
         */
        internal fun assignmentRecords(courses: List<MoodleAssignmentsCourse>): List<MoodleAssignmentRecord> =
            courses.flatMap { course ->
                course.assignments.map { a ->
                    MoodleAssignmentRecord(
                        assignId = a.id,
                        cmid = a.cmid,
                        courseId = course.id,
                        name = a.name.decodeHtmlEntities(),
                        dueDate = a.duedate.coerceAtLeast(0),
                        cutoffDate = (a.cutoffdate ?: 0).coerceAtLeast(0),
                        allowSubmissionsFrom = (a.allowsubmissionsfromdate ?: 0).coerceAtLeast(0),
                        intro = a.intro.orEmpty(),
                        introAttachments = a.introattachments.orEmpty().mapNotNull { f ->
                            MoodleFile(
                                name = f.filename ?: return@mapNotNull null,
                                size = f.filesize ?: 0,
                                url = f.fileurl ?: return@mapNotNull null,
                                mimeType = f.mimetype,
                                modified = f.timemodified?.takeIf { it > 0 },
                                path = f.filepath,
                            )
                        },
                        noSubmissions = a.nosubmissions != 0,
                        teamSubmission = a.teamsubmission != 0,
                    )
                }
            }

        /**
         * The `mod_assign_get_assignments` form for [courseIds]. It asks only
         * for the courses where the user can submit, so Moodle leaves out a
         * course they are a TA in, whose assignments they grade (issue #160).
         */
        internal fun assignmentsParams(courseIds: List<Int>): List<Pair<String, String>> =
            courseIds.mapIndexed { i, id -> "courseids[$i]" to id.toString() } + ("capabilities[0]" to "mod/assign:submit")

        /**
         * `mod_assign_get_assignments` for [courseIds] under [token], through
         * [client]: the courses of Moodle's answer, which can be none. Every
         * other outcome throws, and the callers keep the assignments they
         * have when it does; see [decodeAssignments].
         */
        internal fun requestAssignments(
            client: OkHttpClient,
            token: String,
            courseIds: List<Int>,
        ): List<MoodleAssignmentsCourse> {
            val req = MoodleWebservice.request("mod_assign_get_assignments", token, assignmentsParams(courseIds))
            return decodeAssignments(MoodleWebservice.execute(client, req))
        }

        /**
         * The courses of the `mod_assign_get_assignments` answer in [body],
         * or a throw for a body that is not one: an error, a page from the
         * NTUST edge, JSON without `courses`.
         *
         * No course at all is an answer: Moodle leaves out a course the user
         * cannot submit to without a word, so a TA account whose assignments
         * are all in courses it TAs gets none (issue #160). No course and a
         * warning about one is not: Moodle warns about a course the token's
         * account is not enrolled in, which is what these course ids get with
         * the token of an account that signed in after they were looked up.
         */
        internal fun decodeAssignments(body: String): List<MoodleAssignmentsCourse> {
            MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
            val envelope = try {
                decodeGson.fromJson(body, MoodleAssignmentsEnvelope::class.java)
            } catch (e: Exception) {
                throw MoodleWebserviceError.MalformedResponse("assignments decode failed: ${e.message}")
            }
            val courses = envelope?.courses
                ?: throw MoodleWebserviceError.MalformedResponse("assignments answer without courses")
            if (courses.isEmpty() && envelope.warnings.orEmpty().any { it.item == "course" }) {
                throw MoodleWebserviceError.MalformedResponse("no course answered for")
            }
            return courses
        }

        /**
         * The assignments [cached] records as submitted, by Moodle assignment
         * id, with when they were submitted.
         *
         * One recorded without a time is left out, so it is asked about until
         * Moodle supplies one: the time is all a status call could still add,
         * and without it a late submission never shows as late. The time kept
         * is the first one seen, so a later resubmission's, a late one
         * included, shows only after a pull, which asks about everything.
         */
        /**
         * The assignments of a round Moodle is asked about: not one without a
         * due date or taking no submissions, which the list leaves out
         * anyway; not one the cache has [confirmed]; and not one [closed]
         * (by id, when Moodle said so, epoch ms) within the last
         * [CLOSED_FOR_MS]. As iOS's `AppServiceBridge.recordsToAskAbout`
         * (tigerduck-app/tigerduck-app#257).
         */
        internal fun assignmentsToAsk(
            assignments: List<MoodleAssignmentNode>,
            confirmed: Map<Int, Date>,
            closed: Map<Int, Long>,
            nowMs: Long,
        ): List<MoodleAssignmentNode> = assignments.filter {
            it.duedate > 0 && it.nosubmissions == 0 && it.id !in confirmed && !stillClosed(closed[it.id], nowMs)
        }

        /**
         * How long an assignment found closed is not asked about. Not for
         * good: a teacher can grant an extension or lift a lock afterwards,
         * and the student's clock or Moodle's answer can be wrong.
         */
        internal const val CLOSED_FOR_MS = 24 * 60 * 60 * 1000L

        // Closed at [closedAtMs], and not longer ago than CLOSED_FOR_MS by a
        // clock that has not gone back past it.
        private fun stillClosed(closedAtMs: Long?, nowMs: Long): Boolean {
            closedAtMs ?: return false
            val age = nowMs - closedAtMs
            return age in 0 until CLOSED_FOR_MS
        }

        /**
         * The closed assignments after a round asked at [askedAtMs]: each one
         * Moodle answered not handed in and no longer editable or submittable
         * by this student (`canedit`, `cansubmit`, which take the student's
         * own cutoff, override and extension into account), once its
         * submissions had opened; and each one [kept] from before that this
         * round did not ask about, while still closed. Not one Moodle does
         * not say this of: closed by this phone's clock and the assignment's
         * cutoff, one an extension or an override keeps open was never asked
         * about again.
         */
        internal fun closedUnsubmitted(
            assignments: List<MoodleAssignmentNode>,
            statuses: Map<Int, MoodleSubmissionStatusEnvelope>,
            askedAtMs: Long,
            kept: Map<Int, Long>,
        ): Map<Int, Long> {
            val closed = mutableMapOf<Int, Long>()
            for (assignment in assignments) {
                val status = statuses[assignment.id]
                if (status != null) {
                    val attempt = status.lastattempt ?: continue
                    val opened = (assignment.allowsubmissionsfromdate ?: 0L) * 1000 <= askedAtMs
                    if (attempt.handedIn == null && attempt.canedit == false && attempt.cansubmit != true && opened) {
                        closed[assignment.id] = askedAtMs
                    }
                } else {
                    val keptAt = kept[assignment.id] ?: continue
                    if (stillClosed(keptAt, askedAtMs)) closed[assignment.id] = keptAt
                }
            }
            return closed
        }

        internal fun confirmedSubmissions(cached: List<Assignment>): Map<Int, Date> =
            cached.filter { it.isCompleted }
                .mapNotNull { a ->
                    val id = a.assignmentId.toIntOrNull() ?: return@mapNotNull null
                    a.submittedAt?.let { id to it }
                }
                .toMap()

        /**
         * Whether assignment [id] is submitted, and when: as [confirmed]
         * recorded it when the cache already knew, else as Moodle's
         * [lastAttempt] says — null when its status call failed. Submitted
         * when the student's own submission or their team's is (issue #222).
         */
        /**
         * The extension assignment [id] runs to: as Moodle's [lastAttempt] says when this round
         * [asked] about it, none included, else as [cached] last had it, as a round that skipped the
         * status call, or whose call failed, keeps a confirmed submission.
         */
        internal fun extensionDueDate(
            id: Int,
            asked: Boolean,
            lastAttempt: MoodleSubmissionLastAttempt?,
            cached: Map<String, Date>,
        ): Date? = if (asked) {
            lastAttempt?.extensionduedate?.takeIf { it > 0 }?.let { Date(it * 1000) }
        } else {
            cached[id.toString()]
        }

        internal fun submissionState(
            id: Int,
            confirmed: Map<Int, Date>,
            lastAttempt: MoodleSubmissionLastAttempt?,
        ): Pair<Boolean, Date?> {
            if (id in confirmed) return true to confirmed[id]
            val handedIn = lastAttempt?.handedIn
            val submittedAt = (handedIn ?: lastAttempt?.submission)?.timemodified
                ?.takeIf { it > 0 }?.let { Date(it * 1000) }
            return (handedIn != null) to submittedAt
        }

        /**
         * `mod_assign_get_submission_status` for [assignId] under [token], through
         * [client]: for [userId], or without one for the token's own account.
         */
        internal fun requestSubmissionStatus(
            client: OkHttpClient,
            token: String,
            assignId: Int,
            userId: Int?,
        ): MoodleSubmissionStatusEnvelope {
            val params = listOf("assignid" to assignId.toString()) + listOfNotNull(userId?.let { "userid" to it.toString() })
            val req = MoodleWebservice.request("mod_assign_get_submission_status", token, params)
            return decodeSubmissionStatus(MoodleWebservice.execute(client, req))
        }

        /**
         * The `mod_assign_get_submission_status` answer in [body], or a throw
         * for a body that is not one: an error, a page from the NTUST edge.
         */
        internal fun decodeSubmissionStatus(body: String): MoodleSubmissionStatusEnvelope {
            MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
            return try {
                decodeGson.fromJson(body, MoodleSubmissionStatusEnvelope::class.java)
                    ?: throw MoodleWebserviceError.MalformedResponse("submission status envelope null")
            } catch (e: MoodleWebserviceError) {
                throw e
            } catch (e: Exception) {
                throw MoodleWebserviceError.MalformedResponse("submission status decode failed: ${e.message}")
            }
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
