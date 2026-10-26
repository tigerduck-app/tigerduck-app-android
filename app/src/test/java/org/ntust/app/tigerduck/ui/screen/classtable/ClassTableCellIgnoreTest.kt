package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What a class table cell says and offers about its course's ignored assignments. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class ClassTableCellIgnoreTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val course = Course(courseNo = "CS1001301", courseName = "Calculus")

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun showCell(ignored: Boolean, onToggle: ((Course) -> Unit)?) {
        composeRule.setContent {
            Box(Modifier.size(200.dp)) {
                SoloCourseCell(
                    course = course,
                    roomHint = null,
                    spanCount = 1,
                    dayColWidth = 80.dp,
                    cellHeight = 52.dp,
                    x = 0.dp,
                    y = 0.dp,
                    weekday = 1,
                    periodId = "3",
                    hasAssignment = false,
                    assignmentsIgnored = ignored,
                    onTap = {},
                    onLongPress = {},
                    onRename = {},
                    onPickColor = {},
                    onToggleIgnoreAssignments = onToggle,
                    onDelete = {},
                )
            }
        }
    }

    private fun longPressCell(label: String) {
        composeRule.onNodeWithContentDescription(label).performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    @Test
    fun `an ignored course says so at the end of its TalkBack label`() {
        showCell(ignored = true, onToggle = {})

        composeRule
            .onNodeWithContentDescription(
                "Mon period 3: Calculus. " + text(R.string.a11y_class_table_cell_assignments_ignored)
            )
            .assertExists()
    }

    @Test
    fun `the long-press menu stops ignoring an ignored course`() {
        var toggled: Course? = null
        showCell(ignored = true, onToggle = { toggled = it })

        longPressCell("Mon period 3: Calculus. " + text(R.string.a11y_class_table_cell_assignments_ignored))
        composeRule.onNodeWithText(text(R.string.class_table_ignore_assignments_undo)).performClick()

        assertEquals(course, toggled)
    }

    @Test
    fun `the long-press menu offers to ignore a course that is not`() {
        var toggled: Course? = null
        showCell(ignored = false, onToggle = { toggled = it })

        longPressCell("Mon period 3: Calculus")
        composeRule.onNodeWithText(text(R.string.class_table_ignore_assignments)).performClick()

        assertEquals(course, toggled)
    }

    @Test
    fun `off the term in session the menu leaves the action out`() {
        showCell(ignored = false, onToggle = null)

        longPressCell("Mon period 3: Calculus")

        composeRule.onNodeWithText(text(R.string.class_table_rename_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.class_table_ignore_assignments)).assertDoesNotExist()
    }
}
