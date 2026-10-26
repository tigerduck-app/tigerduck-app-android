// Draws the fills the app paints in the accent itself under a gradient pick
// and reads the pixels back: they run from the accent to the gradient's end,
// the way the gradient was picked to run.

package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarGrid
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the captured image holds actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentGradientFillTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetTheme() {
        TigerDuckTheme.setDarkMode(false)
    }

    private val start = Color(0xFFFF3B30)
    private val end = Color(0xFF007AFF)

    // A light color of the user's own, whose text is dark, running into black:
    // half way, under the day's number, dark text would all but vanish.
    private val yellow = Color(0xFFFFEB3B)
    private val darkText = Color(0xFF1C1C1E)

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) < 0.05f && abs(green - other.green) < 0.05f && abs(blue - other.blue) < 0.05f

    private fun LocalDate.toDate(): Date = Date.from(atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant())

    @Test
    fun `the calendar's selected day runs from the accent to the gradient's end`() {
        composeRule.setContent {
            TigerDuckAppTheme(
                darkTheme = false,
                accentColor = start,
                accentGradient = AccentGradient(end, AccentGradientDirection.LeftToRight),
            ) {
                CalendarGrid(
                    monthDate = LocalDate.of(2026, 10, 1).toDate(),
                    selectedDate = LocalDate.of(2026, 10, 14).toDate(),
                    eventsByDay = emptyMap(),
                    onDateSelected = {},
                )
            }
        }

        val cell = composeRule.onNodeWithContentDescription("Wednesday, October 14, 2026, 0 events")
            .captureToImage().toPixelMap()

        // Across the middle, clear of the day's number and of the pill's
        // round ends: the left reads as the accent, the right as the end.
        val y = cell.height / 2
        val left = cell[cell.width / 6, y]
        val right = cell[cell.width - 1 - cell.width / 6, y]
        assertTrue("redder on the left: $left, $right", left.red - right.red > 0.3f)
        assertTrue("bluer on the right: $left, $right", right.blue - left.blue > 0.3f)
    }

    @Test
    fun `text on a gradient fill reads on its middle, where it sits`() {
        var onFill: Color? = null
        composeRule.setContent {
            TigerDuckAppTheme(
                darkTheme = false,
                accentColor = yellow,
                onAccentColor = darkText,
                accentGradient = AccentGradient(Color.Black, AccentGradientDirection.LeftToRight),
            ) { onFill = TigerDuckTheme.onAccentBrush }
        }
        composeRule.waitForIdle()

        assertEquals(Color.White, onFill)
    }

    @Test
    fun `text on a single color's fill is the theme's text on the accent`() {
        var onFill: Color? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, accentColor = yellow, onAccentColor = darkText) {
                onFill = TigerDuckTheme.onAccentBrush
            }
        }
        composeRule.waitForIdle()

        assertEquals(darkText, onFill)
    }

    @Test
    fun `the calendar's selected day number reads on the middle of the gradient`() {
        composeRule.setContent {
            TigerDuckAppTheme(
                darkTheme = false,
                accentColor = yellow,
                onAccentColor = darkText,
                accentGradient = AccentGradient(Color.Black, AccentGradientDirection.LeftToRight),
            ) {
                CalendarGrid(
                    monthDate = LocalDate.of(2026, 10, 1).toDate(),
                    selectedDate = LocalDate.of(2026, 10, 14).toDate(),
                    eventsByDay = emptyMap(),
                    onDateSelected = {},
                )
            }
        }

        val cell = composeRule.onNodeWithContentDescription("Wednesday, October 14, 2026, 0 events")
            .captureToImage().toPixelMap()
        // Only the pill's middle, clear of the page around its round ends:
        // neither yellow nor black is white there, so only the number can be.
        var white = 0
        for (y in cell.height / 4 until cell.height * 3 / 4) {
            for (x in cell.width * 3 / 10 until cell.width * 7 / 10) if (cell[x, y].near(Color.White)) white++
        }
        assertTrue("the number is white, $white pixels", white > 0)
    }
}
