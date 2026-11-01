package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A grade reads at the theme's titleMedium, bold, in its chip. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class GradeChipTextTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a grade is 18sp and bold`() {
        val course = CourseGrade(term = "1141", code = "CS1001301", name = "Algorithms", grade = "A+")
        var label = ""
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                label = gradeDescriptor(course).first
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
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val style = results.single().layoutInput.style
        assertEquals(18.sp, style.fontSize)
        assertEquals(FontWeight.Bold, style.fontWeight)
    }
}
