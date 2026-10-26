package org.ntust.app.tigerduck.data.moodle

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi

/** What an assignment's page found of its assignment in the cache. */
sealed interface MoodleAssignmentLookup {
    /** The cache has not been read yet. */
    data object Loading : MoodleAssignmentLookup

    /** Neither a round's record nor Home's row names it: it is not this account's homework. */
    data object Missing : MoodleAssignmentLookup

    data class Found(val entry: MoodleAssignmentEntry) : MoodleAssignmentLookup
}

/**
 * The signed-in account's assignments as their pages show them: the records the last assignment
 * round kept, Home's rows for them, and each one's submission, fetched when its page opens. One
 * per account, shared by every page; see [MoodleCourseRepository.assignments]. Ported from iOS
 * `MoodleCourseAssignments`.
 */
class MoodleAccountAssignments(
    private val api: MoodleCourseApi,
    private val context: MoodleCacheContext,
    private val scope: CoroutineScope,
    /** The records of the account's last round, read again after each round. */
    records: Flow<List<MoodleAssignmentRecord>>,
    /** Home's rows, which keep what Moodle said of each submission, read again after each write. */
    listed: Flow<List<Assignment>>,
    /** Takes the status a page fetched for the cached list. */
    private val takeStatus: suspend (SubmissionStatusAnswer) -> Unit,
    /** The cache's generation now; see `DataCache.userDataGeneration`. */
    private val generation: () -> Int,
    /** A monotonic clock, which stamps each request before it goes out. */
    private val monotonicNowMs: () -> Long,
) {
    /** False once the account signed out, after which a page showing these closes. */
    fun isCurrent(): Boolean = context.isCurrent()

    /** Null until the cache is read. */
    val records: StateFlow<List<MoodleAssignmentRecord>?> =
        records.map<List<MoodleAssignmentRecord>, List<MoodleAssignmentRecord>?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Null until the cache is read. */
    val listed: StateFlow<List<Assignment>?> =
        listed.map<List<Assignment>, List<Assignment>?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    /** What the page for [assignmentId] shows, as the cache changes under it. */
    fun lookup(assignmentId: String): Flow<MoodleAssignmentLookup> =
        combine(this.records, this.listed) { records, listed ->
            if (records == null || listed == null) return@combine MoodleAssignmentLookup.Loading
            MoodleAssignmentEntries.of(assignmentId, records, listed)?.let(MoodleAssignmentLookup::Found)
                ?: MoodleAssignmentLookup.Missing
        }.distinctUntilChanged()

    /** The assignments of course [courseId], in Moodle's order, each with Home's row where it has one. */
    fun entries(courseId: Int): Flow<List<MoodleAssignmentEntry>> =
        combine(this.records, this.listed) { records, listed ->
            val rows = listed.orEmpty().associateBy { it.assignmentId }
            records.orEmpty().filter { it.courseId == courseId }
                .map { MoodleAssignmentEntry(it, rows[it.assignId.toString()]) }
        }.distinctUntilChanged()

    private val submissions = HashMap<Int, MoodleFeed<MoodleAssignmentSubmission>>()

    /** When the last fetch of each submission failed, by assignment, for the backoff. */
    private val failedAtMs = HashMap<Int, Long>()

    /**
     * The submission of assignment [assignId]. Its copy's age is judged by [refreshSubmission],
     * not by the feed's timer, which the page does not run.
     */
    @Synchronized
    fun submission(assignId: Int): MoodleFeed<MoodleAssignmentSubmission> = submissions.getOrPut(assignId) {
        MoodleFeed("assignments/$assignId", object : TypeToken<MoodleAssignmentSubmission>() {}.type, ttlMs = 0, context, scope)
    }

    /**
     * The submission as Moodle has it now, with its comments and its group's name where the site
     * lets the app read them. A call Moodle refuses leaves its part out; one that cannot reach
     * Moodle keeps what the page showed. An assignment that takes no online submission has none.
     * The status the page then shows goes to [takeStatus] too, stamped with when it was asked.
     *
     * An opening or a return to the app asks Moodle only once the copy is [SUBMISSION_FRESH_MS]
     * old, or [SUBMISSION_RETRY_MS] after a failed try: a return a few seconds after a success
     * would repeat the status, comment and group requests for nothing. [force] asks at once, for
     * Retry and for the way back from Moodle, where a submission may just have been made: with a
     * request of its own, after any still out, whose answer may be from before the submission.
     */
    suspend fun refreshSubmission(entry: MoodleAssignmentEntry, force: Boolean = false) {
        val record = entry.record
        if (record.noSubmissions) return
        val feed = submission(record.assignId)
        feed.awaitCache()
        val failedAt = synchronized(this) { failedAtMs[record.assignId] }
        if (!force && !submissionRefreshDue(feed.state.value.fetchedAtMs, failedAt, context.now())) return
        feed.refresh(joinRunning = !force) {
            val functions = api.functions()
            val generation = generation()
            val askedAt = monotonicNowMs()
            var submission = api.submission(record.assignId)
            val id = submission.id
            if (id != null && submission.takesComments && MoodleCourseApi.COMMENTS_FUNCTION in functions) {
                // The next account's token would ask Moodle as them.
                if (!context.isCurrent()) throw CancellationException("signed out")
                submission = submission.copy(comments = unlessRefused { api.comments(record.cmid, id) })
            }
            val groupId = submission.groupId
            if (record.teamSubmission && groupId != null && record.courseId > 0 &&
                MoodleCourseApi.GROUPS_FUNCTION in functions
            ) {
                if (!context.isCurrent()) throw CancellationException("signed out")
                submission = submission.copy(groupName = unlessRefused { api.groups(record.courseId) }?.get(groupId))
            }
            if (!context.isCurrent()) throw CancellationException("signed out")
            takeStatus(
                SubmissionStatusAnswer(
                    assignmentId = record.assignId.toString(),
                    submitted = submission.isSubmitted,
                    submittedAtMs = submission.submittedAt?.let { it * 1000 },
                    askedAtMs = askedAt,
                    generation = generation,
                    extensionDueMs = submission.extensionDue?.let { it * 1000 },
                ),
            )
            submission
        }
        synchronized(this) {
            if (feed.state.value.failure != null) failedAtMs[record.assignId] = context.now() else failedAtMs.remove(record.assignId)
        }
    }

    /** Null for an answer that is a Moodle error, or one the app cannot read. */
    private suspend fun <T> unlessRefused(call: suspend () -> T): T? = try {
        call()
    } catch (_: MoodleWebserviceError.AccessDenied) {
        null
    } catch (_: MoodleWebserviceError.MalformedResponse) {
        null
    }

    companion object {
        /** How long a submission copy counts as fresh for an ordinary opening or return. */
        const val SUBMISSION_FRESH_MS = 60_000L

        /** How long a failed fetch holds the next ordinary one back. */
        const val SUBMISSION_RETRY_MS = 30_000L

        /**
         * Whether an ordinary opening or return fetches the submission again: not within
         * [SUBMISSION_FRESH_MS] of the copy's fetch at [fetchedAtMs], nor within
         * [SUBMISSION_RETRY_MS] of a failed try at [failedAtMs]. A time ahead of the clock, left
         * by a clock set back, counts as old.
         */
        internal fun submissionRefreshDue(fetchedAtMs: Long?, failedAtMs: Long?, nowMs: Long): Boolean {
            val fresh = fetchedAtMs?.let { it <= nowMs && nowMs - it < SUBMISSION_FRESH_MS } ?: false
            val backingOff = failedAtMs?.let { it <= nowMs && nowMs - it < SUBMISSION_RETRY_MS } ?: false
            return !fresh && !backingOff
        }
    }
}
