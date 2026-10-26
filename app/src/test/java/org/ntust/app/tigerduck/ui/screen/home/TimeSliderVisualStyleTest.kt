// Draws Home's time slider in a class under each interface style and reads
// the pixels back: TigerDuck keeps its tinted card and track, TAT takes the
// class's pastel for both and marks the class the slider is on in dark ink.

package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.pastel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the captured image holds actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimeSliderVisualStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Period 3 (10:20 to 11:10) every day; the clock stands at 10:40 in it.
    private val calculus = Course.fromSchedule(
        "CS1001", "Calculus", schedule = (1..7).associateWith { listOf("3") },
    )
    private val colour get() = TigerDuckTheme.courseColorVibrant(calculus.courseNo)

    @Before
    fun freezeClock() {
        val tenForty = LocalDate.parse("2026-09-28").atTime(10, 40)
            .atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(ClockOverride(tenForty, frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
        TigerDuckTheme.setDarkMode(false)
    }

    private fun draw(preset: VisualPreset) {
        TigerDuckTheme.setDarkMode(false)
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, visualPreset = preset) {
                // At density 1 a dp is a pixel, so the track's geometry reads straight off the image.
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Box(Modifier.size(320.dp, 320.dp).background(Color.White)) {
                        TimeSliderSection(
                            courses = listOf(calculus),
                            quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
                            invertDirection = false,
                            onSelectCourse = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun pixels(): PixelMap = composeRule.onRoot().captureToImage().toPixelMap()

    private fun track(): Rect {
        val label = composeRule.activity.getString(R.string.home_time_slider_title)
        return composeRule.onNode(hasContentDescription(label, substring = true))
            .fetchSemanticsNode().boundsInRoot
    }

    /** Inside the card's right padding, halfway down: no text reaches it. */
    private fun PixelMap.cardFill(): Color {
        // The row composes its cards twice, once to measure them; both copies
        // report the card's bounds.
        val card = composeRule.onAllNodesWithText(calculus.courseName).onFirst()
            .fetchSemanticsNode().boundsInRoot
        return this[card.right.toInt() - 6, card.center.y.toInt()]
    }

    // On the 36-high track the 18-high segment runs from 9 to 27, and the
    // class began 20 minutes, 18 pixels, left of the centre. These points sit
    // 13 left of the centre, clear of the centre line and the quarter-hour dots.

    private fun PixelMap.segmentFill(track: Rect) = this[track.center.x.toInt() - 13, track.top.toInt() + 13]

    /** The segment's top row, where an outline drawn inside it lies. */
    private fun PixelMap.segmentTopRow(track: Rect) = this[track.center.x.toInt() - 13, track.top.toInt() + 9]

    /** Beside the centre line, on the dot drawn over it. */
    private fun PixelMap.dot(track: Rect) = this[track.center.x.toInt() + 2, track.top.toInt() + 18]

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    /** [ink] at its own alpha over the opaque [under]. */
    private fun over(ink: Color, under: Color) = Color(
        red = ink.red * ink.alpha + under.red * (1 - ink.alpha),
        green = ink.green * ink.alpha + under.green * (1 - ink.alpha),
        blue = ink.blue * ink.alpha + under.blue * (1 - ink.alpha),
    )

    /** The track behind the segments: the light theme's surfaceVariant at 60% on the white page. */
    private val trackBackground = over(Color(0xFFE5E5EA).copy(alpha = 0.6f), Color.White)

    @Test
    fun `TigerDuck keeps the tinted card, the tinted segment and the course-coloured dot`() {
        draw(VisualPreset.TigerDuck)
        val image = pixels()
        val track = track()
        val segment = over(colour.copy(alpha = 0.5f), trackBackground)
        assertNear("card", over(colour.copy(alpha = 0.5f), Color.White), image.cardFill())
        assertNear("segment", segment, image.segmentFill(track))
        assertNear("segment's top row, unoutlined", segment, image.segmentTopRow(track))
        assertNear("dot", colour, image.dot(track))
    }

    @Test
    fun `TAT draws the card and the segment in the pastel, outlines the segment and inks the dot`() {
        draw(VisualPreset.Tat)
        val image = pixels()
        val track = track()
        val pastel = colour.pastel()
        assertNear("card", pastel, image.cardFill())
        assertNear("segment", pastel, image.segmentFill(track))
        assertNear("outline", over(VisualStylePolicy.TextOnPastelSecondary, pastel), image.segmentTopRow(track))
        assertNear("dot", VisualStylePolicy.TextOnPastel, image.dot(track))
    }
}
