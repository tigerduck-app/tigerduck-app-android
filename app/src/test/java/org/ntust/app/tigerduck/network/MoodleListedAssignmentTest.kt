package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.network.model.MoodleAssignmentNode
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import java.util.Date

/**
 * Home's row for each assignment of a round: one Moodle gives no due date (`duedate` 0) is listed
 * with none, as iOS lists it (tigerduck-app#1029), and one taking no submission is not listed.
 */
class MoodleListedAssignmentTest {

    private fun node(id: Int, due: Long, noSubmissions: Boolean = false) = MoodleAssignmentNode(
        id = id,
        cmid = id * 10,
        name = "HW $id &amp; more",
        duedate = due,
        nosubmissions = if (noSubmissions) 1 else 0,
    )

    private fun listed(node: MoodleAssignmentNode, status: MoodleSubmissionStatusEnvelope? = null) =
        MoodleService.listedAssignment(
            node,
            courseNo = "CS1001301",
            courseName = "Calculus",
            semester = "1142",
            confirmed = emptyMap(),
            status = status,
            cachedExtensions = emptyMap(),
        )

    @Test
    fun `one with no due date is listed with none, one taking no submission is not listed`() {
        val undated = listed(node(701, due = 0))
        assertEquals("701", undated?.assignmentId)
        assertEquals("HW 701 & more", undated?.title)
        assertEquals("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=7010", undated?.moodleUrl)
        assertNull(undated?.dueDate)

        assertEquals(Date(1_790_600_000_000L), listed(node(702, due = 1_790_600_000L))?.dueDate)
        assertNull(listed(node(703, due = 0, noSubmissions = true)))
    }

    @Test
    fun `one with no due date and an extension is due on the extension`() {
        val status = MoodleSubmissionStatusEnvelope(lastattempt = MoodleSubmissionLastAttempt(extensionduedate = 1_790_700_000L))

        val extended = listed(node(701, due = 0), status)

        assertNull(extended?.dueDate)
        assertEquals(Date(1_790_700_000_000L), extended?.studentDueDate)
    }
}
