package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The class table's week from today with an assessment and an online class (tigerduck-app
 * `CourseChangeScreensTests`): an assessed class reads the assessment right after its name, in a
 * 衝堂 cluster too, a cancelled one does not, and an online class reads Online for its room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableKindsGridTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private val midterm = mapOf("note_zh" to "期中考", "note_en" to "Midterm")

    private fun show(courses: List<Course>, vararg rows: org.json.JSONObject) {
        composeRule.setContent {
            TimetableGrid(
                courses = courses,
                showRoomHints = true,
                weekdays = listOf(1, 2, 3, 4, 5),
                periods = ClassTableCellLayout.activePeriods(courses),
                courseNosWithAssignments = emptySet(),
                thisWeek = ClassTableWeek(CourseChangeFixture.thursday, CourseChangeFixture.changes(*rows)),
            )
        }
    }

    private fun labels(name: String): List<String> =
        composeRule.onAllNodes(hasContentDescription(name, substring = true)).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }

    @Test
    fun `this week's assessed meeting reads the assessment after the name, and an online one reads online`() {
        show(
            listOf(CourseChangeFixture.course()),
            row(1, "assessment", periods = listOf("2", "3", "4"), values = midterm),
            row(2, "online", periods = listOf("7", "8")),
            row(3, "assessment", periods = listOf("7", "8"), days = listOf("2026-11-12"), values = midterm),
        )
        val assessment = text(R.string.course_change_assessment)
        val online = text(R.string.course_change_online)

        val labels = labels("Compilers")
        assertEquals(labels.toString(), 1, labels.count { "Compilers, $assessment" in it })
        val morning = labels.single { "Compilers, $assessment" in it }
        assertTrue(morning, "T4-501" in morning && "Midterm" !in morning)
        val afternoon = labels.single { online in it }
        assertTrue("next week's assessment is not this week's: $afternoon", assessment !in afternoon && "T4-501" !in afternoon)
    }

    @Test
    fun `a cancelled assessment reads as cancelled, without the assessment`() {
        show(
            listOf(CourseChangeFixture.course()),
            row(1, "assessment", values = midterm),
            row(2, "cancelled", periods = listOf("7", "8")),
        )
        val assessment = text(R.string.course_change_assessment)

        val labels = labels("Compilers")
        val cancelled = labels.single { text(R.string.course_card_cancelled) in it }
        assertTrue(cancelled, assessment !in cancelled)
        assertEquals(1, labels.count { assessment in it })
    }

    @Test
    fun `a 衝堂 pair reads the assessment of the one course that has it`() {
        val parsing = Course.fromSchedule(courseNo = "CC901", courseName = "Parsing", classroom = "T4-502", schedule = mapOf(4 to listOf("3", "4")))
        show(listOf(CourseChangeFixture.course(), parsing), row(1, "assessment", values = midterm, courseNo = "CC901"))
        val assessment = text(R.string.course_change_assessment)

        assertTrue(labels("Parsing").toString(), labels("Parsing").any { "Parsing, $assessment" in it })
        assertTrue(labels("Compilers").toString(), labels("Compilers").none { "Compilers, $assessment" in it })
    }
}
