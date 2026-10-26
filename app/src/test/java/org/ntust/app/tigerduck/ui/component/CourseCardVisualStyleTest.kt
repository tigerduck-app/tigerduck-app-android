// Draws the today carousel's course cards under each interface style and
// reads the pixels back: TigerDuck keeps its see-through tint, TAT takes the
// class table's pastel with dark text, paler for a class already over.

package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.pastel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the captured image holds actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseCardVisualStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val calculusColour get() = TigerDuckTheme.courseColorVibrant(calculus.courseNo)

    // At density 1 a dp is a pixel. The cards are 160 and 220 wide.
    private val height = 100

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
                    LocalDensity provides Density(1f, 1f),
                ) {
                    Box(Modifier.size(240.dp, height.dp).background(Color.White)) { content() }
                }
            }
        }
    }

    private fun courseCard(preset: VisualPreset, isFinished: Boolean = false, dark: Boolean = false) =
        draw(preset, dark) {
            CourseCard(
                course = calculus,
                timeRange = "08:10-10:00",
                isFinished = isFinished,
                onClick = {},
                modifier = Modifier.height(height.dp),
            )
        }

    private fun currentClassCard(preset: VisualPreset, dark: Boolean = false) = draw(preset, dark) {
        CurrentClassCard(
            course = calculus,
            blockStartMinute = 8 * 60 + 10,
            blockEndMinute = 10 * 60,
            currentMinute = 9 * 60,
            onClick = {},
            modifier = Modifier.height(height.dp),
        )
    }

    private fun pixels(): PixelMap = composeRule.onRoot().captureToImage().toPixelMap()

    /** Inside the card's right padding, halfway down: no text reaches it. */
    private fun PixelMap.fillOf(cardWidth: Int): Color = this[cardWidth - 6, height / 2]

    /** The sum of the channels of each pixel the course name's bounds take, card included. */
    private fun PixelMap.nameShades(): List<Float> {
        val bounds = composeRule.onNodeWithText(calculus.courseName, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return (bounds.left.toInt() until bounds.right.toInt()).flatMap { x ->
            (bounds.top.toInt() until bounds.bottom.toInt()).map { y ->
                this[x, y].let { it.red + it.green + it.blue }
            }
        }
    }

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    /** [colour] at [alpha] over white, as a see-through card shows on the test's white page. */
    private fun overWhite(colour: Color, alpha: Float) = Color(
        red = colour.red * alpha + (1 - alpha),
        green = colour.green * alpha + (1 - alpha),
        blue = colour.blue * alpha + (1 - alpha),
    )

    private val tat = VisualStylePolicy(VisualPreset.Tat)

    @Test
    fun `TigerDuck keeps the see-through tint`() {
        courseCard(VisualPreset.TigerDuck)
        assertNear("card", overWhite(calculusColour, 0.5f), pixels().fillOf(160))
    }

    @Test
    fun `TAT fills a card with the class table's pastel`() {
        courseCard(VisualPreset.Tat)
        assertNear("card", calculusColour.pastel(), pixels().fillOf(160))
    }

    @Test
    fun `TAT gives a class already over the muted pastel`() {
        courseCard(VisualPreset.Tat, isFinished = true)
        val muted = tat.courseCardFill(calculusColour, 0.35f, muted = true, surface = Color.Black, isDark = false)
        assertNear("finished card", muted, pixels().fillOf(160))
    }

    // A pastel's channels are each 65% or more, summing to 1.95 at least, so
    // ink well below that is dark text on it; the theme's light text would
    // leave the pastel the darkest thing there.

    @Test
    fun `TAT keeps the pastel and dark text on a dark screen`() {
        courseCard(VisualPreset.Tat, dark = true)
        val image = pixels()
        assertNear("card", calculusColour.pastel(), image.fillOf(160))
        val darkest = image.nameShades().min()
        assertTrue("the name's darkest pixel sums to $darkest", darkest < 1.2f)
    }

    @Test
    fun `TigerDuck writes the name in the theme's light text on a dark screen`() {
        courseCard(VisualPreset.TigerDuck, dark = true)
        val image = pixels()
        val fill = image.fillOf(160).let { it.red + it.green + it.blue }
        val lightest = image.nameShades().max()
        assertTrue("the name's lightest pixel $lightest stands above the card's $fill", lightest > fill + 0.6f)
    }

    @Test
    fun `TAT fills the current class card with the pastel and writes dark text on a dark screen`() {
        currentClassCard(VisualPreset.Tat, dark = true)
        val image = pixels()
        assertNear("card", calculusColour.pastel(), image.fillOf(220))
        val darkest = image.nameShades().min()
        assertTrue("the name's darkest pixel sums to $darkest", darkest < 1.2f)
    }

    @Test
    fun `TigerDuck keeps the current class card's stronger tint`() {
        currentClassCard(VisualPreset.TigerDuck)
        assertNear("card", overWhite(calculusColour, 0.7f), pixels().fillOf(220))
    }
}
