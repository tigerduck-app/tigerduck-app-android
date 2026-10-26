package org.ntust.app.tigerduck.notification

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusWriter
import org.ntust.app.tigerduck.data.moodle.AssignmentStatusWriter.StatusWrite
import org.ntust.app.tigerduck.data.moodle.SubmissionStatusAnswer
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.SubmissionStatusProbe
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton
import org.ntust.app.tigerduck.data.preferences.AppPreferences

/**
 * Whether a reminder that just fired is still due, by
 * [AssignmentReminderPolicy]: the cache first, then one status call to Moodle.
 * What Moodle reports goes into the cache, and the reminders are armed again
 * from it: a submission's others need no call of their own, and an
 * extension's are moved to its date. The demo account's reminders answer from
 * the cache alone, so store reviewers see them.
 */
@Singleton
class AssignmentReminderCheck internal constructor(
    private val dataCache: DataCache,
    private val scheduler: AssignmentNotificationScheduler,
    private val statusProbe: SubmissionStatusProbe,
    private val fixtures: DebugFixtureStore,
    private val reminderPrefs: AppPreferences,
    private val statusWriter: AssignmentStatusWriter,
    /** The clock the writer orders answers by; see [SubmissionStatusAnswer.askedAtMs]. */
    private val monotonicNowMs: () -> Long,
) {
    @Inject
    constructor(
        dataCache: DataCache,
        scheduler: AssignmentNotificationScheduler,
        statusProbe: SubmissionStatusProbe,
        fixtures: DebugFixtureStore,
        reminderPrefs: AppPreferences,
        statusWriter: AssignmentStatusWriter,
    ) : this(dataCache, scheduler, statusProbe, fixtures, reminderPrefs, statusWriter, SystemClock::elapsedRealtime)

    suspend fun verdict(assignmentId: String, armedDueAtMs: Long?): ReminderVerdict {
        // A sign-out while this runs wipes the data; nothing is written back after it.
        val generation = dataCache.userDataGeneration
        val before = cached(assignmentId)
        AssignmentReminderPolicy.cacheDrop(before, armedDueAtMs)
            ?.let { return ReminderVerdict.Drop(it) }
        // The demo account has no Moodle token to ask with: its submission status is the
        // bundled file's, already in the cache, which has the assignment still due.
        if (fixtures.demoMode) return ReminderVerdict.Show

        val moodleId = assignmentId.toIntOrNull()
        val askedAtMs = monotonicNowMs()
        val answer = moodleId?.let { askMoodle(it) } ?: ReminderStatusAnswer.NoAnswer
        // Read again: a mark, an ignore or a new due date can land while Moodle answers.
        var cached = cached(assignmentId)
        var decidedBy: ReminderStatusAnswer = answer
        if (moodleId != null && answer is ReminderStatusAnswer.Answered) {
            when (val write = record(assignmentId, moodleId, answer, askedAtMs, generation)) {
                // What Moodle answered can move the student's own date. Work it says is handed
                // in keeps the date: that is Moodle's to report, as handed in.
                is StatusWrite.Written -> write.assignments.firstOrNull { it.assignmentId == assignmentId }
                    ?.takeUnless { it.isCompleted }
                    ?.let { written -> cached = cached?.copy(dueAtMs = written.studentDueDate.time) }
                StatusWrite.Unchanged -> Unit
                // One asked for later came first: the cache as it stands now has it, and this
                // answer, older, decides nothing.
                StatusWrite.Refused -> {
                    cached = cached(assignmentId)
                    decidedBy = ReminderStatusAnswer.Superseded
                }
            }
        }
        // One armed without its date was armed for the date the cache had before the call.
        return AssignmentReminderPolicy.decide(cached, armedDueAtMs ?: before?.dueAtMs, decidedBy)
    }

    private suspend fun cached(assignmentId: String): CachedReminderAssignment? {
        val assignment = dataCache.loadAssignments().firstOrNull { it.assignmentId == assignmentId }
            ?: return null
        val ofIgnoredCourse = CourseIgnores.assignmentIds(
            listOf(assignment), dataCache.loadIgnoredCourses(), dataCache.currentSemesterCode(),
        )
        return CachedReminderAssignment(
            // As the scheduler armed it, an extension included, or every reminder would read as moved.
            dueAtMs = assignment.studentDueDate.time,
            submitted = assignment.isCompleted,
            markedDone = assignmentId in dataCache.loadMarkedCompletedAssignments(),
            ignored = assignmentId in dataCache.loadIgnoredAssignments(),
            courseIgnored = assignmentId in ofIgnoredCourse,
        )
    }

    /**
     * What Moodle answered, written as an assignment's page writes its answer: the submission and
     * the extension together, and never over an answer asked for later, a round's or a page's.
     * This assignment's reminders are armed again from what it wrote, as the next round can come
     * after the first of them is due; every other assignment's are left as they are. The rest a page's answer tells, the upload among them, waits for the
     * next round: the receiver has seconds.
     */
    private suspend fun record(
        assignmentId: String,
        moodleId: Int,
        answer: ReminderStatusAnswer.Answered,
        askedAtMs: Long,
        generation: Int,
    ): StatusWrite {
        val (submitted, submittedAt) = MoodleService.submissionState(moodleId, emptyMap(), answer.lastAttempt)
        val extension = MoodleService.extensionDueDate(moodleId, asked = true, answer.lastAttempt, emptyMap())
        val status = SubmissionStatusAnswer(
            assignmentId, submitted, submittedAt?.time, askedAtMs, generation, extensionDueMs = extension?.time,
        )
        val write = statusWriter.write(status)
        if (write is StatusWrite.Written) scheduler.rescheduleOne(assignmentId, dataCache, reminderPrefs, generation)
        return write
    }

    private suspend fun askMoodle(moodleId: Int): ReminderStatusAnswer = try {
        statusProbe.status(moodleId)
            ?.let { ReminderStatusAnswer.Answered(it.lastattempt) }
            ?: ReminderStatusAnswer.NoToken
    } catch (e: CancellationException) {
        throw e
    } catch (_: MoodleWebserviceError.InvalidToken) {
        ReminderStatusAnswer.TokenRejected
    } catch (_: MoodleWebserviceError.AccessDenied) {
        // Moodle's first answer to an expired token, before it says `invalidtoken`. The status
        // call is one every token may make, so it means the token, not the function.
        ReminderStatusAnswer.TokenRejected
    } catch (e: Exception) {
        Log.w(TAG, "status call failed: ${e.logSummary()}")
        ReminderStatusAnswer.NoAnswer
    }

    private companion object {
        const val TAG = "AssignmentReminderCheck"
    }
}
