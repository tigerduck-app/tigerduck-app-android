package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Date
import kotlin.math.abs

/**
 * Home's assignment row at the largest font sizes: the status goes under the
 * title, at its start, the title keeps the row's width, and the course's bar
 * spans the row, as on iOS. Below them the row is as it was.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, and a window wider than the row.
@Config(application = Application::class, qualifiers = "w480dp-h800dp")
// Real drawing and text, so the bar's pixels and the lines' widths are real.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssignmentItemLargeTextTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")

    // Submitted, so the row shows a status as well as the due time.
    private val homework = Assignment(
        assignmentId = "1",
        courseNo = calculus.courseNo,
        courseName = calculus.courseName,
        title = "Homework 1: read chapters three to five and write a summary of each",
        dueDate = Date(4_102_444_800_000), // 2100
        isCompleted = true,
    )

    private val submitted get() = composeRule.activity.getString(R.string.assignment_status_submitted)

    /** The row in a 360 wide box, at density 1 so a dp is a pixel, and at [fontScale]. */
    private fun show(fontScale: Float) {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(
                    LocalVisualStylePolicy provides VisualStylePolicy(VisualPreset.TigerDuck),
                    LocalDensity provides Density(1f, fontScale),
                ) {
                    Box(Modifier.width(ROW_WIDTH.dp).background(MaterialTheme.colorScheme.surface)) {
                        AssignmentItem(homework, course = calculus)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(text: String): Rect =
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    // From 1.5 on, the first of them: the threshold itself stacks.
    @Test
    fun `at the largest font sizes the status goes under the title, at its start`() {
        show(fontScale = STACKED_ROW_FONT_SCALE)

        val title = bounds(homework.title)
        val status = bounds(submitted)
        assertTrue("the status is beside the title: $status, title $title", status.top >= title.bottom)
        assertEquals("the status is not at the title's start", title.left, status.left, 0.5f)
    }

    // Beside the status the title wrapped into a strip a few letters wide.
    @Test
    fun `at the largest font sizes the title keeps the row's width`() {
        show(fontScale = 2f)

        // The row less its padding, the bar and the gap after it.
        assertEquals(ROW_WIDTH - 16f * 2 - 4f - 12f, bounds(homework.title).width, 0.5f)
    }

    @Test
    fun `at the largest font sizes the course's bar spans the row`() {
        show(fontScale = 2f)

        val row = composeRule.onNodeWithText(homework.title).captureToImage().toPixelMap()
        val color = TigerDuckTheme.courseColorVibrant(calculus.courseNo)
        // The bar is 4 wide inside 16 of padding, and the row has 8 above and below it.
        assertNear("the bar's top", color, row[16 + 2, 8 + 1])
        assertNear("the bar's bottom", color, row[16 + 2, row.height - 8 - 2])
    }

    @Test
    fun `below the largest font sizes the status stays beside the title`() {
        show(fontScale = 1.3f)

        val title = bounds(homework.title)
        val status = bounds(submitted)
        assertTrue("the status is not beside the title: $status, title $title", status.left >= title.right)
    }

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    private companion object {
        const val ROW_WIDTH = 360
    }
}
