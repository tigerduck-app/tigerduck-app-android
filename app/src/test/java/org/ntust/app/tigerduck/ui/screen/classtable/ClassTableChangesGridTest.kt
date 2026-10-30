package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The class table's blocks show the school's changes on their column's date, as on iOS. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableChangesGridTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    /** CC900 on Thursday: its morning meeting moved, started late and noted, its afternoon one cancelled. */
    private fun week(today: LocalDate = CourseChangeFixture.thursday) = ClassTableWeek(
        today = today,
        changes = CourseChangeFixture.changes(
            row(1, "cancelled", periods = listOf("7", "8")),
            row(2, "room", periods = listOf("2", "3", "4"), values = mapOf("room" to "TR-214")),
            row(3, "start", periods = listOf("2"), values = mapOf("start" to "09:40")),
            row(4, "note", periods = listOf("3"), values = mapOf("note_zh" to "期中考", "note_en" to "Midterm")),
        ),
    )

    private fun show(thisWeek: ClassTableWeek?, nowMinute: Int? = null) {
        val courses = listOf(CourseChangeFixture.course())
        composeRule.setContent {
            TimetableGrid(
                courses = courses,
                showRoomHints = true,
                weekdays = listOf(1, 2, 3, 4, 5),
                periods = ClassTableCellLayout.activePeriods(courses),
                courseNosWithAssignments = emptySet(),
                thisWeek = thisWeek,
                nowMinute = nowMinute,
            )
        }
    }

    private fun labels(): List<String> =
        composeRule.onAllNodes(hasContentDescription("Compilers", substring = true)).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }

    @Test
    fun `a changed meeting tells its new room, time and notes`() {
        show(week())
        val morning = labels().single { "TR-214" in it }
        assertTrue(morning, "09:40 - 12:10" in morning)
        assertTrue(morning, "Midterm" in morning)
    }

    @Test
    fun `a cancelled meeting says so`() {
        show(week())
        assertEquals(1, labels().count { text(R.string.course_card_cancelled) in it })
    }

    @Test
    fun `without the week the blocks read as the timetable`() {
        show(null)
        val labels = labels()
        assertEquals(2, labels.size)
        assertTrue(labels.none { "TR-214" in it || text(R.string.course_card_cancelled) in it || "Midterm" in it })
    }

    @Test
    fun `the now line runs over the week when today is a column`() {
        show(week(), nowMinute = 10 * 60)
        composeRule.onNodeWithTag(NOW_LINE_TAG).assertExists()
    }

    @Test
    fun `there is no now line outside the week, nor on a day the table has no column for`() {
        show(week(today = LocalDate.of(2026, 11, 7)), nowMinute = 10 * 60)
        composeRule.onNodeWithTag(NOW_LINE_TAG).assertDoesNotExist()
    }

    @Test
    fun `there is no now line on a plain timetable`() {
        show(null, nowMinute = 10 * 60)
        composeRule.onNodeWithTag(NOW_LINE_TAG).assertDoesNotExist()
    }
}
