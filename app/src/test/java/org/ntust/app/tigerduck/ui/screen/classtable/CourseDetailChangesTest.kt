package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.timeline.ClassSlot
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeRow
import org.ntust.app.tigerduck.shared.timeline.CourseChangeSummary
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The course detail's 教室 and 時間 cards show the shown day's change, and 課程異動 the days ahead, as on iOS. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseDetailChangesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private val course = CourseChangeFixture.course()

    /** Thursday: the morning meeting moves to TR-214 and the afternoon one is cancelled; a week on, it moves again with a note. */
    private val changes = CourseChangeFixture.changes(
        row(1, "cancelled", periods = listOf("7", "8")),
        row(2, "room", periods = listOf("2", "3", "4"), days = listOf("2026-11-05", "2026-11-12"), values = mapOf("room" to "TR-214")),
        row(3, "note", periods = listOf("3"), days = listOf("2026-11-12"), values = mapOf("note_zh" to "期中考", "note_en" to "Midterm")),
    )

    private fun shown(periods: List<String>): ClassSlot? =
        CourseChangeSummary.shownMeeting(course, null, periods, CourseChangeFixture.thursday, changes)

    private fun rows(shown: ClassSlot?): List<CourseChangeRow> = CourseChangeSummary.rows(
        CourseMeetings.upcomingChanges(course, CourseChangeFixture.thursday, changes), shown, text(R.string.course_card_cancelled), Locale.US,
    )

    private fun show(meeting: ClassSlot?, rows: List<CourseChangeRow>) {
        composeRule.setContent {
            Column {
                CourseDetailContent(
                    course = course,
                    title = "Compilers",
                    classroom = "T4-501",
                    timeRange = "09:10 - 12:10",
                    assignments = emptyList(),
                    onRename = null,
                    onOpenInMoodle = null,
                    meeting = meeting,
                    changeRows = rows,
                )
            }
        }
    }

    @Test
    fun `the cards show the shown meeting's new room, and the list the days ahead`() {
        val morning = shown(listOf("2", "3", "4"))
        show(morning, rows(morning))
        composeRule.onNodeWithText("TR-214").assertExists()
        composeRule.onNodeWithText("T4-501").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.course_detail_changes)).assertExists()
        // Thursday's own move is on the card, so its row tells only the afternoon's cancellation.
        composeRule.onNodeWithText(text(R.string.course_card_cancelled)).assertExists()
        composeRule.onNodeWithText("TR-214 · Midterm").assertExists()
    }

    @Test
    fun `a cancelled meeting's time card says so`() {
        val afternoon = shown(listOf("7", "8"))
        show(afternoon, emptyList())
        composeRule.onNodeWithText(text(R.string.course_card_cancelled)).assertExists()
        composeRule.onNodeWithText("09:10 - 12:10").assertDoesNotExist()
    }

    @Test
    fun `a detail without changes has no changes list`() {
        show(null, emptyList())
        composeRule.onNodeWithText("T4-501").assertExists()
        composeRule.onNodeWithText(text(R.string.course_detail_changes)).assertDoesNotExist()
    }
}
