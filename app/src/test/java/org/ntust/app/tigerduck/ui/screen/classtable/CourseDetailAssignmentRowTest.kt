package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The course detail's assignment rows: a tap opens the assignment's page in the app, which needs
 * no link from Moodle, so a row is a button with or without one. Only the page's own button
 * opens Moodle, and that one hides without a link (tigerduck-app-android#313).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseDetailAssignmentRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assignment(moodleUrl: String?) = Assignment(
        assignmentId = "4567",
        courseNo = "CS1001301",
        courseName = "Calculus",
        title = "Homework 3",
        dueDate = Date(System.currentTimeMillis() + 86_400_000L),
        moodleUrl = moodleUrl,
    )

    private val opened = mutableListOf<Assignment>()

    private fun show(assignment: Assignment) {
        composeRule.setContent {
            Column {
                CourseDetailContent(
                    course = Course(courseNo = "CS1001301", courseName = "Calculus"),
                    title = "Calculus",
                    classroom = "TR-313",
                    timeRange = null,
                    assignments = listOf(assignment),
                    onRename = null,
                    onOpenInMoodle = null,
                    onOpenAssignment = { opened += it },
                )
            }
        }
    }

    @Test
    fun `an assignment with a Moodle page opens its page`() {
        val assignment = assignment("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890")
        show(assignment)

        composeRule.onNodeWithText("Homework 3").assertHasClickAction().performClick()

        assertEquals(listOf(assignment), opened)
    }

    @Test
    fun `one Moodle gave no address opens its page too`() {
        val assignment = assignment(moodleUrl = null)
        show(assignment)

        composeRule.onNodeWithText("Homework 3").assertHasClickAction().performClick()

        assertEquals(listOf(assignment), opened)
    }
}
