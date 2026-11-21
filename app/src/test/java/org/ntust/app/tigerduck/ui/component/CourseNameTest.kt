package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A course name with an assessment, drawn and read back from pixels (tigerduck-app
 * `CourseChangeScreensTests.assessmentUnderline`): dashes in the assessment ink under every line,
 * in light and dark, whatever the text's color, and read with the assessment after the name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseNameTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetTheme() {
        TigerDuckTheme.setDarkMode(false)
    }

    /** "Compilers" in black or white 20sp text on the other, at a dp to a pixel, [lines] wide. */
    private fun draw(isAssessment: Boolean, dark: Boolean = false, text: String = "Compilers", maxLines: Int = 1) {
        TigerDuckTheme.setDarkMode(dark)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.size(200.dp, 120.dp).background(if (dark) Color.Black else Color.White).padding(10.dp)) {
                    CourseName(
                        text = text, isAssessment = isAssessment, color = if (dark) Color.White else Color.Black,
                        fontSize = 20.sp, maxLines = maxLines,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The rows of [ink] pixels, and for each the runs of them along it. */
    private fun inkRuns(ink: Color): Map<Int, Int> {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val rows = mutableMapOf<Int, Int>()
        for (y in 0 until pixels.height) {
            var runs = 0
            var inRun = false
            for (x in 0 until pixels.width) {
                val near = close(pixels[x, y], ink)
                if (near && !inRun) runs++
                inRun = near
            }
            if (runs > 0) rows[y] = runs
        }
        return rows
    }

    private fun close(a: Color, b: Color): Boolean =
        kotlin.math.abs(a.red - b.red) < 0.04f && kotlin.math.abs(a.green - b.green) < 0.04f && kotlin.math.abs(a.blue - b.blue) < 0.04f

    @Test
    fun `an assessment's name has dashes in the light ink under it`() {
        draw(isAssessment = true)

        val runs = inkRuns(AssessmentInk.of(isDark = false))
        assertTrue("ink under the name: $runs", runs.isNotEmpty())
        assertTrue("dashed, not one line: $runs", runs.values.max() >= 5)
        assertTrue("at most a couple of rows thick: $runs", runs.size <= 3)
    }

    @Test
    fun `in dark mode the dashes take the dark ink`() {
        draw(isAssessment = true, dark = true)

        assertTrue(inkRuns(AssessmentInk.of(isDark = true)).isNotEmpty())
        assertTrue(inkRuns(AssessmentInk.of(isDark = false)).isEmpty())
    }

    @Test
    fun `a name of two lines has dashes under each`() {
        draw(isAssessment = true, text = "Compilers Compilers Compilers", maxLines = 2)

        val rows = inkRuns(AssessmentInk.of(isDark = false)).keys.sorted()
        assertTrue("two bands of rows: $rows", rows.last() - rows.first() > 15)
    }

    @Test
    fun `any other name has none`() {
        draw(isAssessment = false)

        assertTrue(inkRuns(AssessmentInk.of(isDark = false)).isEmpty())
    }

    @Test
    fun `TalkBack reads the assessment right after the name, and nothing more for another`() {
        draw(isAssessment = true)
        val assessment = composeRule.activity.getString(R.string.course_change_assessment)
        val read = composeRule.onNodeWithText("Compilers").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Compilers, $assessment"), read)
    }

    @Test
    fun `a name without an assessment keeps its own reading`() {
        draw(isAssessment = false)
        assertNull(
            composeRule.onNodeWithText("Compilers").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }
}
