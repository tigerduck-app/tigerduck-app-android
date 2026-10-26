// Draws each place outside the cards and the class table that shows a course's
// color, under each interface style, and reads the pixels back: TigerDuck
// keeps the color as it is, TAT takes the class table's pastel.

package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.CourseDetailContent
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.pastel
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Date
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the captured image holds actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseAccentVisualStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val statistics = Course(courseNo = "MA2002", courseName = "Statistics")
    private val calculusColor get() = TigerDuckTheme.courseColorVibrant(calculus.courseNo)

    private val homework = Assignment(
        assignmentId = "1",
        courseNo = calculus.courseNo,
        courseName = calculus.courseName,
        title = "Homework 1",
        dueDate = Date(4_102_444_800_000), // 2100
    )

    @After
    fun resetTheme() {
        TigerDuckTheme.setDarkMode(false)
    }

    private fun draw(preset: VisualPreset, dark: Boolean = false, content: @Composable () -> Unit) {
        TigerDuckTheme.setDarkMode(dark)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalVisualStylePolicy provides VisualStylePolicy(preset),
                    // At density 1 a dp is a pixel.
                    LocalDensity provides Density(1f, 1f),
                ) {
                    Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) { content() }
                }
            }
        }
    }

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    // ---- an assignment's bar, on Home ----

    private fun assignmentBar(): Color {
        // The row is one node to TalkBack, its bar 4 wide inside its 16 of padding.
        val row = composeRule.onNodeWithText(homework.title).captureToImage().toPixelMap()
        return row[16 + 2, row.height / 2]
    }

    @Test
    fun `TigerDuck draws an assignment's bar in its course's color`() {
        draw(VisualPreset.TigerDuck) { AssignmentItem(homework, course = calculus) }
        assertNear("bar", calculusColor, assignmentBar())
    }

    @Test
    fun `TAT draws an assignment's bar in the course's pastel`() {
        draw(VisualPreset.Tat) { AssignmentItem(homework, course = calculus) }
        assertNear("bar", calculusColor.pastel(), assignmentBar())
    }

    @Test
    fun `TAT keeps the pastel bar on a dark screen`() {
        draw(VisualPreset.Tat, dark = true) { AssignmentItem(homework, course = calculus) }
        assertNear("bar", calculusColor.pastel(), assignmentBar())
    }

    // ---- the bar under a course's name, in its sheet ----

    private fun drawDetail(preset: VisualPreset, dark: Boolean = false) = draw(preset, dark) {
        Column {
            CourseDetailContent(
                course = calculus,
                title = calculus.courseName,
                classroom = "TR-313",
                timeRange = null,
                assignments = emptyList(),
                onRename = null,
                onOpenInMoodle = null,
            )
        }
    }

    private fun detailBar(): Color {
        // Without its buttons the title's row is the title alone; the 6-high bar is 12 below it.
        val title = composeRule.onNodeWithText(calculus.courseName, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val page = composeRule.onRoot().captureToImage().toPixelMap()
        return page[20, title.bottom.toInt() + 12 + 3]
    }

    @Test
    fun `TigerDuck draws the course sheet's bar in the course's color for the theme`() {
        drawDetail(VisualPreset.TigerDuck, dark = true)
        assertNear("bar", TigerDuckTheme.courseColor(calculus.courseNo), detailBar())
    }

    @Test
    fun `TAT draws the course sheet's bar in the course's pastel on a dark screen too`() {
        drawDetail(VisualPreset.Tat, dark = true)
        assertNear("bar", calculusColor.pastel(), detailBar())
    }

    // ---- the swatch in the conflict picker ----

    private fun drawConflictPicker(preset: VisualPreset, dark: Boolean = false) = draw(preset, dark) {
        ConflictCoursePickerSheet(
            courseA = calculus,
            courseB = statistics,
            weekday = 1,
            onPick = {},
            onDismiss = {},
        )
    }

    private fun conflictSwatch(): Color {
        // The row is one tappable node, its 16-wide swatch inside its 16 of padding.
        val row = composeRule.onNode(hasText(calculus.courseName) and hasClickAction()).captureToImage().toPixelMap()
        return row[16 + 8, row.height / 2]
    }

    @Test
    fun `TigerDuck draws the conflict picker's swatch in the course's color for the theme`() {
        drawConflictPicker(VisualPreset.TigerDuck, dark = true)
        assertNear("swatch", TigerDuckTheme.courseColor(calculus.courseNo), conflictSwatch())
    }

    @Test
    fun `TAT draws the conflict picker's swatch in the course's pastel`() {
        drawConflictPicker(VisualPreset.Tat, dark = true)
        assertNear("swatch", calculusColor.pastel(), conflictSwatch())
    }

    // ---- the color picker ----

    private val coral = courseColorPalette[0]
    private val teal = courseColorPalette[1]

    private fun drawColorPicker(preset: VisualPreset, dark: Boolean = false) = draw(preset, dark) {
        ColorPickerSheet(
            // No name, so the preview shows its fallback and nothing else does.
            courseName = "",
            currentColor = teal,
            presetPalette = listOf(coral, teal),
            usedByOthers = emptySet(),
            onApply = {},
            onDismiss = {},
        )
    }

    /** The middle of the first swatch, which is not the chosen one. */
    private fun swatch(): Color = composeRule.onNodeWithContentDescription("Color 1").captureToImage().toPixelMap()
        .let { it[it.width / 2, it.height / 2] }

    /** The preview's fill: the color most of the pixels behind its text take. */
    private fun preview(): Color {
        val fallback = RuntimeEnvironment.getApplication().getString(R.string.color_picker_preview_fallback)
        val pixels = composeRule.onNodeWithText(fallback).captureToImage().toPixelMap()
        val all = (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y] } }
        return all.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    @Test
    fun `TigerDuck offers each color as it is and previews it see-through`() {
        drawColorPicker(VisualPreset.TigerDuck)
        assertNear("swatch", coral, swatch())
        val sheet = lightColorScheme().surface
        val seeThrough = Color(
            red = teal.red * 0.85f + sheet.red * 0.15f,
            green = teal.green * 0.85f + sheet.green * 0.15f,
            blue = teal.blue * 0.85f + sheet.blue * 0.15f,
        )
        assertNear("preview", seeThrough, preview())
    }

    @Test
    fun `the picker offers the dark variants in dark mode, but not under TAT, whose pastel is of the light color`() {
        assertTrue(VisualStylePolicy(VisualPreset.TigerDuck).colorPickerOffersDarkPalette(isDark = true))
        assertTrue(!VisualStylePolicy(VisualPreset.TigerDuck).colorPickerOffersDarkPalette(isDark = false))
        assertTrue(!VisualStylePolicy(VisualPreset.Tat).colorPickerOffersDarkPalette(isDark = true))
        assertTrue(!VisualStylePolicy(VisualPreset.Tat).colorPickerOffersDarkPalette(isDark = false))
    }

    @Test
    fun `TAT offers each color as its pastel and previews the chosen one so`() {
        drawColorPicker(VisualPreset.Tat, dark = true)
        assertNear("swatch", coral.pastel(), swatch())
        assertNear("preview", teal.pastel(), preview())
    }

    /**
     * The chosen swatch's ring, inside the 40-wide circle's top edge, and the
     * sums of the channels of the 18-wide square in its middle, where the
     * check is. Both lie inside the circle, clear of the sheet around it.
     */
    private fun chosenSwatchMarks(): Pair<Color, List<Float>> {
        val pixels = composeRule.onNodeWithContentDescription("Color 2").captureToImage().toPixelMap()
        val (middleX, middleY) = pixels.width / 2 to pixels.height / 2
        // The ring is 3 wide, so a pixel 1 below the top edge is on it.
        val ring = pixels[middleX, 1]
        val check = (middleX - 9 until middleX + 9).flatMap { x ->
            (middleY - 9 until middleY + 9).map { y -> pixels[x, y].let { it.red + it.green + it.blue } }
        }
        return ring to check
    }

    @Test
    fun `TigerDuck marks the chosen swatch in white`() {
        drawColorPicker(VisualPreset.TigerDuck)
        val (ring, check) = chosenSwatchMarks()
        assertNear("ring", Color.White, ring)
        // The swatch's teal sums to under 2, so only the check reaches this.
        assertTrue("the check's lightest pixel sums to ${check.max()}", check.max() > 2.9f)
    }

    // A pastel's channels are each 65% or more, summing to 1.95 at least, so
    // ink well below that is a dark mark on it; a white one would leave the
    // pastel the darkest thing there.

    @Test
    fun `TAT marks the chosen swatch in dark ink on its pastel`() {
        drawColorPicker(VisualPreset.Tat)
        val (ring, check) = chosenSwatchMarks()
        assertNear("ring", VisualStylePolicy.TextOnPastel, ring)
        assertTrue("the check's darkest pixel sums to ${check.min()}", check.min() < 1.2f)
    }
}
