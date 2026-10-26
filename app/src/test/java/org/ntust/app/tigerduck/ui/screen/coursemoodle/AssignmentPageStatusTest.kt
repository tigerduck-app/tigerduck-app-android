package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentEntry
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.Locale

/** What the page says of the deadline, the hand-in and the grade, as iOS says it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AssignmentPageStatusTest {

    private val due = 1_790_000_000L
    private val hour = 3_600L
    private val day = 24 * hour

    private fun record(noSubmissions: Boolean = false) = MoodleAssignmentRecord(
        assignId = 701, cmid = 9101, courseId = 20119, name = "HW", dueDate = due, cutoffDate = 0, allowSubmissionsFrom = 0,
        intro = "", introAttachments = emptyList(), noSubmissions = noSubmissions, teamSubmission = false,
    )

    private fun listed(completed: Boolean) = Assignment(
        assignmentId = "701", courseNo = "CS3005301", courseName = "DS", title = "HW", dueDate = Date(due * 1000), isCompleted = completed,
    )

    private fun submission(status: String?, modified: Long? = null, text: String? = null) = MoodleAssignmentSubmission(
        status = status, modified = modified, files = emptyList(), onlineText = text, id = 1, takesComments = false,
        groupId = null, groupName = null, comments = null, grade = null,
    )

    @Test
    fun `whether it is handed in comes from the fetched submission, else Home's row, and never for one taking nothing online`() {
        assertEquals(true, AssignmentPageStatus.isSubmitted(MoodleAssignmentEntry(record(), listed(false)), submission("submitted")))
        assertEquals(false, AssignmentPageStatus.isSubmitted(MoodleAssignmentEntry(record(), listed(true)), submission("draft")))
        assertEquals(true, AssignmentPageStatus.isSubmitted(MoodleAssignmentEntry(record(), listed(true)), null))
        assertNull(AssignmentPageStatus.isSubmitted(MoodleAssignmentEntry(record(), null), null))
        assertNull(AssignmentPageStatus.isSubmitted(MoodleAssignmentEntry(record(noSubmissions = true), listed(true)), submission("submitted")))
    }

    @Test
    fun `the badge says not submitted, submitted, or how early or late it was handed in`() {
        assertNull(AssignmentPageStatus.badge(isSubmitted = null, submittedAtSeconds = null, dueSeconds = due))
        assertEquals(SubmissionBadge.NotSubmitted, AssignmentPageStatus.badge(false, null, due))
        assertEquals(SubmissionBadge.Submitted, AssignmentPageStatus.badge(true, null, due))
        assertEquals(SubmissionBadge.Submitted, AssignmentPageStatus.badge(true, due - hour, null))
        assertEquals(SubmissionBadge.Early(hour), AssignmentPageStatus.badge(true, due - hour, due))
        assertEquals(SubmissionBadge.Late(2 * hour), AssignmentPageStatus.badge(true, due + 2 * hour, due))
    }

    @Test
    fun `the line under the due time counts down, urgent while not handed in, and overdue only while not handed in`() {
        assertEquals(DeadlineLine.Left(day, urgent = true), AssignmentPageStatus.deadline(due, isSubmitted = false, nowSeconds = due - day))
        assertEquals(DeadlineLine.Left(day, urgent = false), AssignmentPageStatus.deadline(due, isSubmitted = true, nowSeconds = due - day))
        assertEquals(DeadlineLine.Left(day, urgent = false), AssignmentPageStatus.deadline(due, isSubmitted = null, nowSeconds = due - day))
        assertEquals(DeadlineLine.Overdue(hour), AssignmentPageStatus.deadline(due, isSubmitted = false, nowSeconds = due + hour))
        assertEquals(DeadlineLine.Passed, AssignmentPageStatus.deadline(due, isSubmitted = true, nowSeconds = due + hour))
        assertEquals(DeadlineLine.Passed, AssignmentPageStatus.deadline(due, isSubmitted = null, nowSeconds = due + hour))
    }

    @Test
    fun `the card shows for files, text or a hand-in time, not for an empty record`() {
        assertTrue(AssignmentPageStatus.showsCard(submission("submitted", modified = due)))
        assertTrue(AssignmentPageStatus.showsCard(submission("draft", modified = due, text = "<p>x</p>")))
        assertFalse(AssignmentPageStatus.showsCard(submission("draft", modified = due)))
        assertFalse(AssignmentPageStatus.showsCard(submission(null)))
    }

    @Test
    fun `points over a maximum split without their trailing zeros, any other grade stays whole`() {
        assertEquals(Score("85", "100"), AssignmentPageStatus.score("85.00 / 100.00"))
        assertEquals(Score("7.5", "10"), AssignmentPageStatus.score("7.50 / 10.00"))
        assertEquals(Score("A+", null), AssignmentPageStatus.score("A+"))
        assertEquals(Score("85 %", null), AssignmentPageStatus.score("85 %"))
        assertEquals(Score("Pass / Fail", null), AssignmentPageStatus.score("Pass / Fail"))
    }

    @Test
    fun `a length of time reads in at most two units, rounded down, and never under a minute`() {
        assertEquals("1 day, 4 hr", AssignmentPageStatus.durationText(day + 4 * hour + 59 * 60 + 59, Locale.US))
        assertEquals("2 days", AssignmentPageStatus.durationText(2 * day, Locale.US))
        assertEquals("3 hr, 5 min", AssignmentPageStatus.durationText(3 * hour + 5 * 60 + 30, Locale.US))
        assertEquals("45 min", AssignmentPageStatus.durationText(45 * 60, Locale.US))
        assertEquals("1 min", AssignmentPageStatus.durationText(20, Locale.US))
        assertEquals("2 days, 30 min", AssignmentPageStatus.durationText(2 * day + 30 * 60, Locale.US))
    }

    @Test
    fun `the page counts to the extension the student was granted`() {
        val extended = listed(false).copy(extensionDueDate = Date((due + day) * 1000))

        assertEquals(due + day, AssignmentPageRules.dueSeconds(MoodleAssignmentEntry(record(), extended)))
        assertEquals(due, AssignmentPageRules.dueSeconds(MoodleAssignmentEntry(record(), listed(false))))
    }
}
