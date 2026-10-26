// Draws the GPA trend card under a chosen accent and reads the chart's pixels
// back: the line and points are the accent, not the fixed teal they once were.

package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.GpaTrendPoint
import org.ntust.app.tigerduck.data.model.RankingStats
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the chart turns into actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RankingsTrendColorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pink = Color(0xFFFF2D55)
    private val oldTeal = Color(0xFF4ECDC4)

    private fun point(term: String, gpa: Double, provisional: Boolean = false) = GpaTrendPoint(
        term = term,
        semester = RankingStats(gpa = gpa),
        cumulative = RankingStats(gpa = gpa),
        isProvisional = provisional,
    )

    private fun Color.near(other: Color) =
        abs(red - other.red) < 0.04f && abs(green - other.green) < 0.04f && abs(blue - other.blue) < 0.04f

    private fun countBelowHeader(dark: Boolean, color: Color): Int {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = dark, accentColor = pink) {
                RankingsTrendCard(
                    points = listOf(point("1131", 3.2), point("1132", 3.6), point("1141", 3.9, provisional = true)),
                    scope = ScoreViewModel.RankingScope.SEMESTER,
                    onScopeChange = {},
                )
            }
        }
        // The scope picker above the chart is in the accent too; only what is below it counts.
        val title = composeRule.onNodeWithText(composeRule.activity.getString(R.string.score_gpa_trend_title))
            .fetchSemanticsNode().boundsInRoot
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var count = 0
        for (y in (title.bottom.toInt() + 24) until pixels.height) {
            for (x in 0 until pixels.width) if (pixels[x, y].near(color)) count++
        }
        return count
    }

    @Test
    fun `the trend is drawn in the accent, in the light theme`() {
        assertEquals("nothing is left in the old fixed teal", 0, countBelowHeader(dark = false, color = oldTeal))
    }

    @Test
    fun `the trend is drawn in the accent, in the dark theme`() {
        assertEquals(0, countBelowHeader(dark = true, color = oldTeal))
    }

    @Test
    fun `the accent draws the line and its points`() {
        val accentPixels = countBelowHeader(dark = false, color = pink)
        assertTrue("only $accentPixels accent pixels in the chart", accentPixels > 200)
    }
}
