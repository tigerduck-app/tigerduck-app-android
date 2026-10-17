package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentStatus
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentEntry
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentLists
import org.ntust.app.tigerduck.data.moodle.MoodleFeed
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentGrade
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/** What an assignment's page and a course's list say of an assignment. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleAssignmentScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /** Markdown from Moodle's HTML is converted off the main thread, so its text lands a moment after the rest. */
    private fun markdownNode(text: String): SemanticsNodeInteraction {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        return composeRule.onNodeWithText(text, substring = true)
    }

    private fun record(noSubmissions: Boolean = false, cmid: Int = 9101) = MoodleAssignmentRecord(
        assignId = 701, cmid = cmid, courseId = 20119, name = "HW 2: Linked lists", dueDate = 1_789_000_740L,
        cutoffDate = 0, allowSubmissionsFrom = 0, intro = "<p>Implement a <strong>singly linked list</strong>.</p>",
        introAttachments = listOf(
            MoodleFile("spec.pdf", 1_258_291, "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/mod_assign/introattachment/0/spec.pdf", "application/pdf", 1L, "/"),
        ),
        noSubmissions = noSubmissions, teamSubmission = true,
    )

    private fun listed(completed: Boolean = false, moodleUrl: String? = "https://moodle2.ntust.edu.tw/mod/assign/view.php?id=9101") = Assignment(
        assignmentId = "701", courseNo = "CS3005301", courseName = "Data Structures", title = "HW 2: Linked lists",
        dueDate = Date(1_789_000_740_000L), isCompleted = completed, moodleUrl = moodleUrl,
    )

    private val notSubmitted = MoodleAssignmentSubmission(
        status = "new", modified = null, files = emptyList(), onlineText = null, id = null,
        takesComments = false, groupId = null, groupName = null, comments = null, grade = null,
    )

    private val submitted = MoodleAssignmentSubmission(
        status = "submitted", modified = 1_788_900_000L,
        files = listOf(MoodleFile("hw2.pdf", 870_400, "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/123/assignsubmission_file/submission_files/5501/hw2.pdf", "application/pdf", 1L, "/")),
        onlineText = "<p>See the attached report.</p>", id = 5501, takesComments = true, groupId = 31, groupName = "Group 3",
        comments = listOf(MoodleSubmissionComment(901, "T00001 @ Duck Teacher", 1_788_950_000L, "<p>Please check page 2.</p>")),
        grade = null,
    )

    @Test
    fun `the page shows the description, the attachment, the due time and that nothing was handed in`() {
        composeRule.setContent {
            // As the page's Scaffold lays them out: the body over the button.
            Column {
                Box(Modifier.weight(1f)) {
                    AssignmentPageBody(
                        entry = MoodleAssignmentEntry(record(), listed()),
                        courseName = "Data Structures",
                        submission = MoodleFeed.State(value = notSubmitted, fetchedAtMs = 1L, cacheLoaded = true),
                        store = null,
                        browserPreference = "system",
                        onRetry = {},
                    )
                }
                AssignmentSubmitButton(MoodleAssignmentEntry(record(), listed()))
            }
        }

        composeRule.onNodeWithText("HW 2: Linked lists").assertIsDisplayed()
        composeRule.onNodeWithText("Data Structures").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_due)).assertIsDisplayed()
        composeRule.onNodeWithText(MoodleDates.weekdayTime(1_789_000_740L)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_description)).performScrollTo().assertIsDisplayed()
        markdownNode("singly linked list").performScrollTo().assertIsDisplayed()
        // A file row reads as one element named for its file; see MoodleFileRow.
        composeRule.onNodeWithContentDescription("spec.pdf").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_not_submitted)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_submit_on_moodle)).assertIsDisplayed()
    }

    @Test
    fun `a submission shows its files, text, time, group and comments`() {
        composeRule.setContent {
            AssignmentPageBody(
                entry = MoodleAssignmentEntry(record(), listed(completed = true)),
                courseName = "Data Structures",
                submission = MoodleFeed.State(value = submitted, fetchedAtMs = 1L, cacheLoaded = true),
                store = null,
                browserPreference = "system",
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Group 3").assertIsDisplayed()
        // Handed in 100,740 s before the due time.
        val early = string(R.string.assignment_detail_submitted_early).format(AssignmentPageStatus.durationText(100_740L))
        composeRule.onNodeWithText(early).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_submitted_at)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(MoodleDates.dayTime(1_788_900_000L)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("hw2.pdf").performScrollTo().assertIsDisplayed()
        markdownNode("See the attached report.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_comments)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Duck Teacher", substring = true).performScrollTo().assertIsDisplayed()
        markdownNode("Please check page 2.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_not_submitted)).assertDoesNotExist()
    }

    @Test
    fun `a graded submission shows the score, when it was graded and the feedback`() {
        val graded = submitted.copy(
            grade = MoodleAssignmentGrade("85.00 / 100.00", 1_789_100_000L, "<p>Case 7 misses the <strong>empty</strong> tree.</p>"),
        )
        composeRule.setContent {
            AssignmentPageBody(
                entry = MoodleAssignmentEntry(record(), listed(completed = true)),
                courseName = "Data Structures",
                submission = MoodleFeed.State(value = graded, fetchedAtMs = 1L, cacheLoaded = true),
                store = null,
                browserPreference = "system",
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.assignment_detail_grade)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("85").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("/ 100").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_graded_at)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(MoodleDates.dayTime(1_789_100_000L)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_feedback)).performScrollTo().assertIsDisplayed()
        markdownNode("Case 7 misses the").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a draft shows its time as a draft's, under a not-submitted badge`() {
        val draft = notSubmitted.copy(status = "draft", modified = 1_788_800_000L, onlineText = "<p>Half done</p>")
        composeRule.setContent {
            AssignmentPageBody(
                entry = MoodleAssignmentEntry(record(), listed()),
                courseName = "Data Structures",
                submission = MoodleFeed.State(value = draft, fetchedAtMs = 1L, cacheLoaded = true),
                store = null,
                browserPreference = "system",
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.assignment_detail_not_submitted)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_draft)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(MoodleDates.dayTime(1_788_800_000L)).performScrollTo().assertIsDisplayed()
        markdownNode("Half done").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_detail_submitted_at)).assertDoesNotExist()
    }

    @Test
    fun `an assignment that takes no online submission shows neither the submission nor the button`() {
        val entry = MoodleAssignmentEntry(record(noSubmissions = true), null)
        composeRule.setContent {
            Column {
                AssignmentPageBody(entry, "Data Structures", MoodleFeed.State(cacheLoaded = true), null, "system", onRetry = {})
                AssignmentSubmitButton(entry)
            }
        }

        composeRule.onNodeWithText(string(R.string.assignment_detail_submission)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.assignment_detail_submit_on_moodle)).assertDoesNotExist()
    }

    @Test
    fun `the button opens the assignment by its course module, else by the row's link, and hides without either`() {
        assertEquals("/mod/assign/view.php?id=9101", AssignmentPageRules.submitPath(MoodleAssignmentEntry(record(), null)))
        assertEquals(
            "/mod/assign/view.php?id=9101",
            AssignmentPageRules.submitPath(MoodleAssignmentEntry(record(cmid = 0), listed())),
        )
        assertNull(AssignmentPageRules.submitPath(MoodleAssignmentEntry(record(cmid = 0), listed(moodleUrl = null))))
        assertNull(AssignmentPageRules.submitPath(MoodleAssignmentEntry(record(cmid = 0), listed(moodleUrl = "https://elsewhere.example/x"))))
        assertNull(AssignmentPageRules.submitPath(MoodleAssignmentEntry(record(noSubmissions = true), listed())))
    }

    @Test
    fun `before the first answer the round's time shows for a submission it found, and nothing otherwise`() {
        val found = MoodleAssignmentEntry(record(), listed(completed = true).copy(submittedAt = Date(1_788_000_000_000L)))
        assertEquals(1_788_000_000L, AssignmentPageRules.submittedSeconds(found, null))
        assertNull(AssignmentPageRules.submittedSeconds(MoodleAssignmentEntry(record(), listed()), null))
        assertNull("a draft's time is not a submission's", AssignmentPageRules.submittedSeconds(found, notSubmitted.copy(status = "draft", modified = 5L)))
        assertEquals(1_788_900_000L, AssignmentPageRules.submittedSeconds(found, submitted))
    }

    @Test
    fun `the Submit button tells the page it handed off to Moodle`() {
        var handedOff = 0
        composeRule.setContent { AssignmentSubmitButton(MoodleAssignmentEntry(record(), listed()), onOpen = { handedOff++ }) }

        composeRule.onNodeWithText(string(R.string.assignment_detail_submit_on_moodle)).performClick()

        assertEquals(1, handedOff)
    }

    @Test
    fun `a list row shows the status Moodle gives and the due time`() {
        composeRule.setContent {
            AssignmentListRow(
                MoodleAssignmentLists.Row(MoodleAssignmentEntry(record(), listed(completed = true)), AssignmentStatus.SUBMITTED),
                onClick = {},
            )
        }

        composeRule.onNodeWithText("HW 2: Linked lists").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.assignment_status_submitted)).assertIsDisplayed()
        composeRule.onNodeWithText(MoodleDates.minute(1_789_000_740L)).assertIsDisplayed()
    }
}
