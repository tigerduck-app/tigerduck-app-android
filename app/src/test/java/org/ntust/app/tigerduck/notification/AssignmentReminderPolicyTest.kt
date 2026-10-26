package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import org.ntust.app.tigerduck.notification.ReminderDropReason.COURSE_IGNORED
import org.ntust.app.tigerduck.notification.ReminderDropReason.DUE_DATE_CHANGED
import org.ntust.app.tigerduck.notification.ReminderDropReason.EXTENDED
import org.ntust.app.tigerduck.notification.ReminderDropReason.IGNORED
import org.ntust.app.tigerduck.notification.ReminderDropReason.LOCKED
import org.ntust.app.tigerduck.notification.ReminderDropReason.MARKED_DONE
import org.ntust.app.tigerduck.notification.ReminderDropReason.NOT_IN_CACHE
import org.ntust.app.tigerduck.notification.ReminderDropReason.NO_ANSWER
import org.ntust.app.tigerduck.notification.ReminderDropReason.NO_TOKEN
import org.ntust.app.tigerduck.notification.ReminderDropReason.SUBMISSIONS_DISABLED
import org.ntust.app.tigerduck.notification.ReminderDropReason.SUBMITTED_IN_CACHE
import org.ntust.app.tigerduck.notification.ReminderDropReason.SUBMITTED_ON_MOODLE
import org.ntust.app.tigerduck.notification.ReminderDropReason.TOKEN_REJECTED
import org.ntust.app.tigerduck.notification.ReminderDropReason.UNKNOWN_STATUS

class AssignmentReminderPolicyTest {

    private val dueAtMs = 1_760_486_400_000L
    private val due = CachedReminderAssignment(
        dueAtMs = dueAtMs,
        submitted = false,
        markedDone = false,
        ignored = false,
        courseIgnored = false,
    )

    private fun answered(
        own: String? = "new",
        team: String? = null,
        enabled: Boolean? = true,
        locked: Boolean? = false,
        extensionSeconds: Long? = null,
    ) = ReminderStatusAnswer.Answered(
        MoodleSubmissionLastAttempt(
            submission = own?.let { MoodleSubmission(it, 1_760_000_000) },
            teamsubmission = team?.let { MoodleSubmission(it, 1_760_000_000) },
            submissionsenabled = enabled,
            locked = locked,
            extensionduedate = extensionSeconds,
        )
    )

    private fun decide(
        cached: CachedReminderAssignment? = due,
        armedDueAtMs: Long? = dueAtMs,
        answer: ReminderStatusAnswer = answered(),
    ) = AssignmentReminderPolicy.decide(cached, armedDueAtMs, answer)

    private fun drop(reason: ReminderDropReason) = ReminderVerdict.Drop(reason)

    // --- shown ------------------------------------------------------------

    @Test
    fun `an assignment still due in the cache and on Moodle shows`() {
        assertEquals(ReminderVerdict.Show, decide())
        assertEquals(ReminderVerdict.Show, decide(answer = answered(own = "draft")))
        assertEquals(ReminderVerdict.Show, decide(answer = answered(own = "reopened")))
    }

    @Test
    fun `a team assignment the team has yet to hand in shows`() {
        assertEquals(ReminderVerdict.Show, decide(answer = answered(own = null, team = "new")))
        assertEquals(ReminderVerdict.Show, decide(answer = answered(own = "new", team = "draft")))
    }

    @Test
    fun `fields Moodle left out do not drop it`() {
        assertEquals(
            ReminderVerdict.Show,
            decide(answer = answered(enabled = null, locked = null, extensionSeconds = null)),
        )
    }

    @Test
    fun `an extension no later than the due date still shows`() {
        assertEquals(ReminderVerdict.Show, decide(answer = answered(extensionSeconds = 0)))
        assertEquals(ReminderVerdict.Show, decide(answer = answered(extensionSeconds = dueAtMs / 1000)))
    }

    @Test
    fun `an alarm armed without its due date goes by the cache's`() {
        assertEquals(ReminderVerdict.Show, decide(armedDueAtMs = null))
        assertEquals(
            drop(EXTENDED),
            decide(armedDueAtMs = null, answer = answered(extensionSeconds = dueAtMs / 1000 + 1)),
        )
    }

    // --- dropped by the cache ----------------------------------------------

    @Test
    fun `the cache drops it before Moodle is asked`() {
        assertEquals(NOT_IN_CACHE, AssignmentReminderPolicy.cacheDrop(null, dueAtMs))
        assertEquals(
            SUBMITTED_IN_CACHE,
            AssignmentReminderPolicy.cacheDrop(due.copy(submitted = true), dueAtMs),
        )
        assertEquals(MARKED_DONE, AssignmentReminderPolicy.cacheDrop(due.copy(markedDone = true), dueAtMs))
        assertEquals(IGNORED, AssignmentReminderPolicy.cacheDrop(due.copy(ignored = true), dueAtMs))
        assertEquals(
            COURSE_IGNORED,
            AssignmentReminderPolicy.cacheDrop(due.copy(courseIgnored = true), dueAtMs),
        )
        assertEquals(DUE_DATE_CHANGED, AssignmentReminderPolicy.cacheDrop(due, dueAtMs - 3_600_000))
        assertNull(AssignmentReminderPolicy.cacheDrop(due, dueAtMs))
        assertNull(AssignmentReminderPolicy.cacheDrop(due, armedDueAtMs = null))
    }

    @Test
    fun `whatever Moodle says, the cache's reason stands`() {
        assertEquals(drop(NOT_IN_CACHE), decide(cached = null))
        assertEquals(drop(SUBMITTED_IN_CACHE), decide(cached = due.copy(submitted = true)))
        assertEquals(drop(MARKED_DONE), decide(cached = due.copy(markedDone = true)))
        assertEquals(drop(IGNORED), decide(cached = due.copy(ignored = true)))
        assertEquals(drop(COURSE_IGNORED), decide(cached = due.copy(courseIgnored = true)))
        assertEquals(drop(DUE_DATE_CHANGED), decide(armedDueAtMs = dueAtMs + 86_400_000))
    }

    // --- dropped by Moodle --------------------------------------------------

    @Test
    fun `no answer from Moodle drops it`() {
        assertEquals(drop(NO_TOKEN), decide(answer = ReminderStatusAnswer.NoToken))
        assertEquals(drop(TOKEN_REJECTED), decide(answer = ReminderStatusAnswer.TokenRejected))
        assertEquals(drop(NO_ANSWER), decide(answer = ReminderStatusAnswer.NoAnswer))
    }

    @Test
    fun `a submission handed in by the student or the team drops it`() {
        assertEquals(drop(SUBMITTED_ON_MOODLE), decide(answer = answered(own = "submitted")))
        assertEquals(drop(SUBMITTED_ON_MOODLE), decide(answer = answered(own = null, team = "submitted")))
        assertEquals(drop(SUBMITTED_ON_MOODLE), decide(answer = answered(own = "new", team = "submitted")))
        // Handed in counts first, locked or not.
        assertEquals(
            drop(SUBMITTED_ON_MOODLE),
            decide(answer = answered(own = "submitted", locked = true)),
        )
    }

    @Test
    fun `an answer without a status still to hand in drops it`() {
        assertEquals(drop(UNKNOWN_STATUS), decide(answer = ReminderStatusAnswer.Answered(null)))
        assertEquals(drop(UNKNOWN_STATUS), decide(answer = answered(own = "marked")))
        assertEquals(
            drop(UNKNOWN_STATUS),
            decide(
                answer = ReminderStatusAnswer.Answered(
                    MoodleSubmissionLastAttempt(submission = MoodleSubmission(status = null))
                )
            ),
        )
    }

    @Test
    fun `an assignment the student never started is still due`() {
        // No submission record at all: Moodle's "No attempt", still to hand in like new.
        assertEquals(ReminderVerdict.Show, decide(answer = answered(own = null, team = null)))
        assertEquals(drop(SUBMISSIONS_DISABLED), decide(answer = answered(own = null, enabled = false)))
        assertEquals(drop(LOCKED), decide(answer = answered(own = null, locked = true)))
        assertEquals(
            drop(EXTENDED),
            decide(answer = answered(own = null, extensionSeconds = dueAtMs / 1000 + 1)),
        )
    }

    @Test
    fun `a real answer for an assignment never started shows`() {
        val attempt = MoodleService.decodeSubmissionStatus(
            """
            {"lastattempt":{
              "submissiongroupmemberswhoneedtosubmit":[],
              "submissionsenabled":true,"locked":false,"graded":false,"canedit":true,
              "caneditowner":true,"cansubmit":false,"extensionduedate":null,"timelimit":0,
              "blindmarking":false,"gradingstatus":"notgraded","usergroups":[]},
             "assignmentdata":{},"warnings":[]}
            """.trimIndent()
        ).lastattempt

        assertEquals(ReminderVerdict.Show, decide(answer = ReminderStatusAnswer.Answered(attempt)))
    }

    @Test
    fun `an assignment the student can no longer hand in drops it`() {
        assertEquals(drop(SUBMISSIONS_DISABLED), decide(answer = answered(enabled = false)))
        assertEquals(drop(LOCKED), decide(answer = answered(locked = true)))
    }

    @Test
    fun `an extension past the due date drops it`() {
        assertEquals(drop(EXTENDED), decide(answer = answered(extensionSeconds = dueAtMs / 1000 + 1)))
    }
}
