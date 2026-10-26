package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The swipes on a Home assignment row, offered to TalkBack and other
 * accessibility services as actions, since they cannot drag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SwipeableAssignmentRowA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val assignment = Assignment(
        assignmentId = "a1",
        courseNo = "CS1001301",
        courseName = "Calculus",
        title = "Homework 3",
        dueDate = Date(System.currentTimeMillis() + 86_400_000L),
    )

    private var marked = 0
    private var ignored = 0

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(
        isIgnored: Boolean = false,
        isMarkedCompleted: Boolean = false,
        swipeEnabled: Boolean = true,
        assignment: Assignment = this.assignment,
    ) {
        composeRule.setContent {
            SwipeableAssignmentRow(
                assignment = assignment,
                course = null,
                isIgnored = isIgnored,
                isMarkedCompleted = isMarkedCompleted,
                swipeEnabled = swipeEnabled,
                showAbsoluteTime = false,
                onClick = {},
                onToggleIgnore = { ignored++ },
                onMarkCompleted = { marked++ },
            )
        }
    }

    private fun actions() = composeRule.onNodeWithText("Homework 3", useUnmergedTree = false)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.CustomActions)
        .orEmpty()

    @Test
    fun `the row offers marking done and ignoring as actions`() {
        show()

        val actions = actions()
        assertEquals(
            listOf(text(R.string.assignment_mark_complete), text(R.string.assignment_ignore)),
            actions.map { it.label },
        )
        actions.forEach { it.action() }
        composeRule.waitForIdle()

        assertEquals(1, marked)
        assertEquals(1, ignored)
    }

    @Test
    fun `a marked and ignored row offers to undo both`() {
        show(isIgnored = true, isMarkedCompleted = true)

        assertEquals(
            listOf(text(R.string.assignment_mark_complete_undo), text(R.string.assignment_ignore_undo)),
            actions().map { it.label },
        )
    }

    @Test
    fun `a row that takes no swipes offers no actions`() {
        show(swipeEnabled = false)

        assertEquals(emptyList<String>(), actions().map { it.label })
    }

    @Test
    fun `a row with a Moodle page is a button`() {
        show(assignment = assignment.copy(moodleUrl = "https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890"))

        composeRule.onNodeWithText("Homework 3").assertHasClickAction()
    }

    @Test
    fun `a row Moodle gave no address is a button too, and keeps its actions`() {
        // The tap opens the assignment's page in the app, which needs no link
        // from Moodle; only the page's own button opens Moodle (#313). The
        // row still reads as one element, with its swipes.
        show()

        composeRule.onNodeWithText("Homework 3").assertHasClickAction()
        assertEquals(
            listOf(text(R.string.assignment_mark_complete), text(R.string.assignment_ignore)),
            actions().map { it.label },
        )
    }
}
