package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A course's detail on the score page shows what the class table knows of the course, or says
 * it knows nothing, as the iPhone's sheet does (tigerduck-app-android#270).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class ScoreCourseDetailDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = CourseGrade(
        term = "1141",
        code = "CS1001301",
        name = "Calculus",
        credits = 3f,
        grade = "A",
        status = GradeStatus.GRADED,
    )

    private val noRoster = "No enrollment data available for this course"
    private val rosterLabels = listOf("Instructor", "Enrollment", "Classroom", "Class schedule")

    private fun show(course: CourseGrade = calculus, lookupRoster: suspend (CourseGrade) -> Course?) {
        composeRule.setContent {
            CourseDetailDialog(course = course, lookupRoster = lookupRoster, onDismiss = {})
        }
    }

    @Test
    fun `a course the class table knows shows its instructor, enrolment, classroom and schedule`() {
        val roster = Course.fromSchedule(
            courseNo = "CS1001301",
            courseName = "Calculus",
            instructor = "Prof. Lin",
            classroom = "TR-313, TR-313",
            enrolledCount = 35,
            maxCount = 60,
            schedule = mapOf(1 to listOf("3", "4"), 2 to listOf("1")),
        )
        show { roster }

        rosterLabels.forEach { composeRule.onNodeWithText(it).assertExists() }
        listOf("Prof. Lin", "35 / 60", "TR-313", "M3, M4, T1").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
        composeRule.onNodeWithText(noRoster).assertDoesNotExist()
    }

    @Test
    fun `a course the class table does not know says so`() {
        show { null }

        composeRule.onNodeWithText(noRoster).assertExists()
        rosterLabels.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    /** A roster course with no instructor, cap, room or periods shows no empty rows, as on iOS. */
    @Test
    fun `roster rows with nothing to say are left out`() {
        show { Course.fromSchedule(courseNo = "CS1001301", courseName = "Calculus") }

        (rosterLabels + noRoster).forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    /**
     * A cache row DataCache accepts with only a number and a name: Gson's Unsafe path leaves
     * instructor and classroom null despite their non-null types (CLAUDE.md), and the dialog
     * must read them as empty rather than crash.
     */
    @Test
    fun `a sparse cached row shows no roster rows and does not crash`() {
        val json = """[{"courseNo":"CS1001301","courseName":"Calculus"}]"""
        val type = object : TypeToken<List<Course>>() {}.type
        val sparse = DataCache.usableRows(Gson().fromJson(json, type)).single()
        show { sparse }

        (rosterLabels + noRoster).forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
        composeRule.onNodeWithText("CS1001301").assertExists()
    }

    /** The "no roster" card must not flash before the cache has answered for a course it knows. */
    @Test
    fun `nothing about the roster shows before the cache answers`() {
        show { awaitCancellation() }

        (rosterLabels + noRoster).forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
        composeRule.onNodeWithText("CS1001301").assertExists()
    }

    @Test
    fun `the header reads the code, term, credits and grade`() {
        show { null }

        composeRule.onNodeWithText("CS1001301").assertExists()
        composeRule.onNodeWithText("Academic Year 114 Fall · 3 credits").assertExists()
        composeRule.onNodeWithText("A").assertExists()
    }

    @Test
    fun `the school's note is shown when there is one`() {
        show(calculus.copy(remark = "補考")) { null }

        composeRule.onNodeWithText("Note").assertExists()
        composeRule.onNodeWithText("補考").assertExists()
    }

    @Test
    fun `no note row without a note`() {
        show { null }

        composeRule.onNodeWithText("Note").assertDoesNotExist()
    }
}
