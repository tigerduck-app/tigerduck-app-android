package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.CreditType
import org.ntust.app.tigerduck.data.model.GradeStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A course row on the score page: the grade chip's status icon, and the credit type and GE
 * dimension as badges beside the meta line, as on iOS (tigerduck-app-android#270).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class ScoreCourseRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = CourseGrade(
        term = "1141",
        code = "CS1001301",
        name = "Calculus",
        credits = 3f,
        creditType = CreditType.NORMAL,
        grade = "A",
        status = GradeStatus.GRADED,
    )

    private fun show(course: CourseGrade) {
        composeRule.setContent {
            SemesterSection(
                term = "1141",
                courses = listOf(course),
                gpaPoint = null,
                isCollapsed = false,
                onToggle = {},
                onCourseTap = {},
            )
        }
    }

    @Test
    fun `status icons go with pending, withdrawn, exempted and pass-fail grades only`() {
        assertEquals(Icons.Outlined.HourglassEmpty, gradeStatusIcon(calculus.copy(status = GradeStatus.PENDING)))
        assertEquals(Icons.Outlined.Cancel, gradeStatusIcon(calculus.copy(status = GradeStatus.WITHDREW)))
        assertEquals(Icons.Outlined.Verified, gradeStatusIcon(calculus.copy(status = GradeStatus.EXEMPTED)))
        assertEquals(
            Icons.Filled.Check,
            gradeStatusIcon(calculus.copy(grade = "通過", status = GradeStatus.PASS_FAIL_GRADED)),
        )
        assertEquals(
            Icons.Filled.Close,
            gradeStatusIcon(calculus.copy(grade = "不通過", status = GradeStatus.PASS_FAIL_GRADED)),
        )
        assertNull(gradeStatusIcon(calculus))
        assertNull(gradeStatusIcon(calculus.copy(grade = "", status = GradeStatus.UNKNOWN)))
    }

    /** The icon is decorative: the label names the status, and TalkBack must not say it twice. */
    @Test
    fun `a pending grade is read once`() {
        show(calculus.copy(grade = "", status = GradeStatus.PENDING))

        composeRule.onNodeWithText("Pending").assertExists()
        composeRule.onAllNodesWithContentDescription("Pending", useUnmergedTree = true).assertCountEquals(0)
    }

    /** "EP" is for the eye; TalkBack gets the credit type in full. */
    @Test
    fun `the credit type is a badge beside the meta line that TalkBack reads in full`() {
        show(calculus.copy(creditType = CreditType.EDUCATION_PROGRAM))

        composeRule.onNodeWithContentDescription("Education program", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("CS1001301 · 3 credits", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the GE dimension is a badge of its own, not part of the meta line`() {
        show(calculus.copy(geDimension = "C"))

        composeRule.onNodeWithText("C", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("CS1001301 · 3 credits", useUnmergedTree = true).assertExists()
    }
}
