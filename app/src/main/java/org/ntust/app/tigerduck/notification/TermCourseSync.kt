// The background's fetch of one term's courses: the worker's refresh of the term in session, and
// the answer to the backend's request for a term's roster (`roster_requests`,
// tigerduck-app-android#595), which Home's full sync and the worker's both read. Out of the
// worker so the two run the one fetch, and so it can be driven in a test.

package org.ntust.app.tigerduck.notification

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.CourseRosterMerge
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.push.CourseUpload
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RosterRequestLog
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableCourseMerge
import org.ntust.app.tigerduck.ui.screen.home.commitFetchedTerm
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermCourseSync internal constructor(
    private val dataCache: DataCache,
    private val sources: Sources,
    private val uploader: Uploader,
    /** `AuthTokenManager.signInGeneration`, which a sign-out moves on. */
    private val signInGeneration: () -> Int,
    private val waitingAdds: suspend (semester: String) -> Set<String>,
    /** `PendingCourseAdds.uploading`: [block] run with no course taken off the backend meanwhile. */
    private val uploading: suspend (block: suspend () -> Unit) -> Unit,
    private val courseLocale: () -> String,
    private val uploadFailed: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        courseService: CourseService,
        moodleService: MoodleService,
        semesterCatalog: SemesterCatalog,
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        authTokenManager: AuthTokenManager,
        pendingCourseAdds: PendingCourseAdds,
        prefs: AppPreferences,
    ) : this(
        dataCache = dataCache,
        sources = object : Sources {
            // 選課 serves exactly one term and its 選課清單 page carries no term marker, so its
            // course numbers belong to whichever term the catalogue reports as open. That runs
            // ahead of the term in session, and filing them under it would put the next term's
            // enrolments in this term's cache.
            override fun servesSelection(semester: String) = semester == semesterCatalog.selectionSemesterCode()

            override suspend fun selection(studentId: String, password: String, fresh: Boolean): List<String> {
                // A pull's reason too: what the school says now, not an answer a screen fetched a minute ago.
                if (fresh) courseService.expireSharedResults()
                return courseService.fetchEnrolledCourseNos(studentId, password)
            }

            override suspend fun moodle(): List<MoodleEnrolledCourse> = moodleService.fetchEnrolledCourses()

            override suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course? =
                courseService.lookupOrFallback(semester, courseNo, moodle, kept = kept).course
        },
        uploader = Uploader { courses, semester, resend, rosterTerms, signIn ->
            pushApiClient.uploadCourses(courses, semester, signIn = signIn, resend = resend, rosterTerms = rosterTerms)
        },
        signInGeneration = { authTokenManager.signInGeneration },
        waitingAdds = pendingCourseAdds::waitingIn,
        uploading = { block -> pendingCourseAdds.uploading(block) },
        courseLocale = { AppLanguageManager.resolvedCourseApiLanguage(prefs.appLanguage) },
        uploadFailed = { prefs.setLastSyncSource(SyncSource.LOCAL) },
    )

    /** What a term's fetch reads from the school; the services in the app. */
    internal interface Sources {
        /** Whether course selection serves [semester]: its list page names no term of its own. */
        fun servesSelection(semester: String): Boolean

        /** Course selection's answer; throws when it cannot be had. [fresh] asks the school, past a shared answer. */
        suspend fun selection(studentId: String, password: String, fresh: Boolean): List<String>

        /** Moodle's enrolments, every term's; throws when it cannot be had. */
        suspend fun moodle(): List<MoodleEnrolledCourse>

        /** One course as the school has it, else [kept], else Moodle's stand-in; see `CourseService.lookupOrFallback`. */
        suspend fun lookUp(semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): Course?
    }

    /**
     * Uploads a term's courses, with the token of sign-in [signIn] alone; see
     * `PushApiClient.uploadCourses`.
     */
    internal fun interface Uploader {
        suspend fun upload(courses: List<Course>, semester: String, resend: Boolean, rosterTerms: List<String>, signIn: Int)
    }

    // One for the process, Home's full syncs and the worker's alike.
    private val rosterRequestLog = RosterRequestLog()

    /**
     * Answers a full sync's [requests], the terms the backend asks this account's roster of
     * (`roster_requests`), as [studentId]: each term [sync]ed as [rosterRequested], once per
     * request, and again only an hour on or for another account ([RosterRequestLog]). Returns
     * whether any went to the school, which may have changed the term's courses.
     *
     * [signIn] is the sign-in the requests came for: after a sign-out none is asked, and none
     * goes up as the account signed in since.
     */
    suspend fun answerRosterRequests(
        requests: List<String>,
        studentId: String,
        password: String,
        signIn: Int = signInGeneration(),
    ): Boolean {
        if (signInGeneration() != signIn) return false
        val terms = rosterRequestLog.termsToAsk(requests, dataCache.userDataGeneration, clock())
        for (term in terms) {
            Log.i(TAG, "roster requested for $term")
            sync(studentId, password, term, rosterRequested = true, signIn = signIn)
        }
        return terms.isNotEmpty()
    }

    /**
     * [semester]'s courses fetched, committed and uploaded. Course selection's answer when it
     * serves the term, else the transcript's classes added to the term (on disk: the background
     * never signs in to the score system), else the term stays as it is (tigerduck-app#890).
     * Moodle gives names and Moodle IDs only: it lists course sites and keeps a dropped class.
     * Returns false for a fetch to retry: course selection serves the term and could not be
     * reached, and nothing stands in for it.
     *
     * The upload goes as the term's roster when course selection answered it, and lists every
     * course of the answer that is not hidden here; see [CourseUpload.of].
     *
     * [rosterRequested] is the answer to the backend's request for the term's roster: course
     * selection is asked afresh, and the upload goes past the ledger, as the backend asks
     * because none arrived for a week, unchanged or not. Course selection not serving the term,
     * or giving nothing, sends nothing. As iOS `AppServiceBridge.answerRosterRequests`
     * (tigerduck-app#914).
     *
     * [signIn], read before the fetch, is the sign-in the term is fetched for. The upload goes
     * with its token alone, and after a sign-out nothing is committed or sent: the next
     * account's token would take this roster as that account's and drop its courses.
     *
     * Course selection's answer, replaced by a newer one while the fetch ran, commits and sends
     * nothing ([DataCache.isLatestAnswer]): it would enroll again what the newer one dropped.
     */
    suspend fun sync(
        studentId: String,
        password: String,
        semester: String,
        rosterRequested: Boolean = false,
        signIn: Int = signInGeneration(),
    ): Boolean {
        val servesSelection = sources.servesSelection(semester)
        if (rosterRequested && !servesSelection) return true
        // Before the fetch: a sign-out while it runs drops its roster.
        val generation = dataCache.writeGeneration()
        val (selectionNos, moodleAll) = coroutineScope {
            val selectionDef = async {
                if (!servesSelection) return@async emptyList()
                try {
                    sources.selection(studentId, password, fresh = rosterRequested)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch enrolled course numbers: ${e.logSummary()}")
                    null
                }
            }
            val moodleDef = async {
                try {
                    sources.moodle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch Moodle enrolled courses: ${e.logSummary()}")
                    null
                }
            }
            selectionDef.await() to moodleDef.await()
        }
        // Nothing answered the request: the backend asks again until a roster arrives.
        if (rosterRequested && selectionNos.isNullOrEmpty()) return true

        // Before the merge below rewrites the cache — see DataCache.recordSelectionRoster. The
        // fetch's version of the term: a newer answer recorded meanwhile supersedes its rows.
        val answer = selectionNos?.let { dataCache.recordSelectionRoster(semester, it, generation) }

        // Names and Moodle IDs only: Moodle lists course sites and keeps
        // a dropped class, so it adds no course to the term.
        val moodleByNo = CourseRosterMerge.moodleCoursesFor(semester, moodleAll).associateBy { it.courseNo }
        // The list the merge below folds onto.
        val before = dataCache.loadCourses(semester)
        // Course selection's answer, else the transcript's classes added
        // to the term, else the term stays as it is (tigerduck-app#890).
        // The transcript on disk: the background never signs in to the
        // score system.
        val term = TranscriptTerms.termFrom(selectionNos, dataCache.loadScoreReport(studentId)?.report, semester, before)
            // Course selection serves the term and could not be reached,
            // and nothing stands in for it: retried.
            ?: return selectionNos != null
        // A course whose query fails, or brings no rows, keeps its row in
        // the term, where Moodle's stand-in would take its times and room
        // (#417), and the upload below would take them off every device.
        val cachedByNo = before.associateBy { it.courseNo }
        val looked = coroutineScope {
            term.courseNos.map { courseNo ->
                async { sources.lookUp(semester, courseNo, moodleByNo[courseNo], kept = cachedByNo[courseNo]) }
            }.awaitAll().filterNotNull()
        }
        // A class the transcript adds joins with class times; course selection's all do.
        val fetched = TranscriptTerms.drawnCourses(looked, fromSelection = term.roster != null, cached = cachedByNo.keys)
        if (fetched.isEmpty()) return true

        // Through the class table's merge, onto the term as it is
        // now: user-picked colors, renames and manually-added courses
        // survive, and a course added here keeps having no Moodle ID.
        val unresolvedNos = term.courseNos.toSet() - fetched.mapTo(mutableSetOf()) { it.courseNo }
        // Committed, built and sent in the order a reset's DELETE runs in, as the class table's
        // fetch is, from what the reset left: a reset between the commit and the upload would be
        // undone by the list committed before it, its hand-added courses back on the backend.
        uploading {
            // Signed out during the fetch, or while the lock was waited for: the rows are the account's that left.
            if (signInGeneration() != signIn) return@uploading
            // A reset takes the names of the term's courses with it.
            val names = dataCache.loadCourseCustomNames()
            val locale = courseLocale()
            // Uploaded as written: what the commit left out stays off the server too.
            val merged = commitFetchedTerm(
                dataCache, semester, fetched, term.onlyGains, answer,
                waitingAdds = { waitingAdds(semester) },
            ) { rows, current, hidden ->
                ClassTableCourseMerge.mergeFetched(
                    rows,
                    cached = current,
                    names = names,
                    locale = locale,
                    deletedNos = hidden,
                    // A term only gaining removes nothing, a course saved while
                    // these lookups ran included; otherwise a roster course a
                    // transient lookup failure left unresolved keeps its row.
                    alsoKeeps = { term.onlyGains || it.courseNo in unresolvedNos },
                )
            } ?: return@uploading
            val upload = CourseUpload.of(
                merged, semester, roster = term.roster,
                hidden = CourseTombstoneKeys.hiddenIn(semester, dataCache.loadDeletedCourseNos()),
            )
            // Superseded since the commit: the newer answer's fetch sends the term's roster.
            if (answer != null && !dataCache.isLatestAnswer(semester, answer)) return@uploading
            try {
                // Past the ledger only as the roster asked for; anything less goes as any fetch's.
                uploader.upload(upload.courses, semester, resend = rosterRequested && upload.rosterTerms.isNotEmpty(), upload.rosterTerms, signIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not the next account's sync state: its own sync has not failed.
                if (signInGeneration() == signIn) uploadFailed()
                Log.w(TAG, "uploadCourses failed (non-fatal): ${e.logSummary()}")
            }
        }
        return true
    }

    private companion object {
        const val TAG = "BackgroundSyncWorker"
    }
}
