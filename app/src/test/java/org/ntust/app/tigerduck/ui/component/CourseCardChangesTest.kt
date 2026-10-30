package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.course
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The class table's today strip cards on a day the school changed: CC900's morning, periods 2 to 4. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class CourseCardChangesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val morning = listOf("2", "3", "4")

    private fun decorationOf(text: String): TextDecoration? {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.textDecoration
    }

    @Test
    fun `a cancelled class is struck through and says so`() {
        val meeting = CourseMeetings.meeting(course(), morning, thursday, changes(row(1, "cancelled", periods = morning)))
        composeRule.setContent {
            CourseCard(course = course(), timeRange = "09:10-12:10", weekday = 4, periods = morning, change = meeting, onClick = {})
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.course_card_cancelled)).assertExists()
        composeRule.onNodeWithText("T4-501").assertDoesNotExist()
        assertEquals(TextDecoration.LineThrough, decorationOf("Compilers"))
        assertEquals(TextDecoration.LineThrough, decorationOf("09:10-12:10"))
    }

    @Test
    fun `a moved class shows its new room`() {
        val meeting = CourseMeetings.meeting(
            course(), morning, thursday, changes(row(1, "room", periods = morning, values = mapOf("room" to "TR-313"))),
        )
        composeRule.setContent {
            CourseCard(course = course(), timeRange = "09:10-12:10", weekday = 4, periods = morning, change = meeting, onClick = {})
        }

        composeRule.onNodeWithText("TR-313", substring = true).assertExists()
        composeRule.onNodeWithText("T4-501").assertDoesNotExist()
        assertEquals(null, decorationOf("Compilers"))
    }

    @Test
    fun `the current class card shows the room the class moved to`() {
        val meeting = CourseMeetings.meeting(
            course(), morning, thursday, changes(row(1, "room", periods = morning, values = mapOf("room" to "TR-313"))),
        )
        composeRule.setContent {
            CurrentClassCard(
                course = course(),
                blockStartMinute = 9 * 60 + 10,
                blockEndMinute = 12 * 60 + 10,
                currentMinute = 10 * 60 + 30,
                weekday = 4,
                periods = morning,
                change = meeting,
                onClick = {},
            )
        }

        composeRule.onNodeWithText("TR-313", substring = true).assertExists()
        composeRule.onNodeWithText("T4-501").assertDoesNotExist()
    }
}
