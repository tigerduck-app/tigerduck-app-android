package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

/** A course of someone else's class table offers no edit menu: its cell has no long press. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableReadOnlyCellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val course = Course(courseNo = "MA2012301", courseName = "Probability")

    private fun show(onLongPress: (() -> Unit)?, onTap: () -> Unit = {}) {
        composeRule.setContent {
            Box(Modifier.size(200.dp)) {
                SoloCourseCell(
                    course = course, roomHint = null, spanCount = 1, dayColWidth = 80.dp, cellHeight = 52.dp,
                    x = 0.dp, y = 0.dp, weekday = 2, hasAssignment = false, assignmentsIgnored = false,
                    onTap = onTap, onLongPress = onLongPress, onRename = {}, onPickColor = {},
                    onToggleIgnoreAssignments = null, onDelete = {},
                )
            }
        }
    }

    private val rename get() = RuntimeEnvironment.getApplication().getString(R.string.class_table_rename_title)

    @Test
    fun `a read-only cell opens no menu on a long press`() {
        show(onLongPress = null)

        composeRule.onNodeWithContentDescription("Probability").performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(rename).assertDoesNotExist()
    }

    @Test
    fun `an editable cell opens its menu on a long press`() {
        show(onLongPress = {})
        composeRule.onNodeWithContentDescription("Probability").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rename).assertExists()
    }

    @Test
    fun `a read-only cell still opens the detail on a tap`() {
        var taps = 0
        show(onLongPress = null, onTap = { taps++ })
        composeRule.onNodeWithContentDescription("Probability").performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals(1, taps)
    }
}
