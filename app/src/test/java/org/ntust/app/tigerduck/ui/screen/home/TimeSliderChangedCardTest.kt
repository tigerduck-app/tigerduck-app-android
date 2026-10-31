package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.at
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.course
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's card for the class the slider is on, on a Thursday the school changed it. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class TimeSliderChangedCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var feed by mutableStateOf(CourseChanges.EMPTY)

    @Before
    fun freezeClock() {
        AppClock.setOverride(ClockOverride(at("10:30").toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                TimeSliderSection(
                    courses = listOf(course()),
                    quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
                    invertDirection = false,
                    courseChanges = feed,
                    onSelectCourse = {},
                )
            }
        }
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun shows(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun decorationOf(text: String): TextDecoration? {
        val node = composeRule.onAllNodesWithText(text).onFirst().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.textDecoration
    }

    @Test
    fun `a cancelled class is struck through and says so, once the feed cancels it`() {
        val cancelled = composeRule.activity.getString(R.string.course_card_cancelled)
        assertTrue(!shows(cancelled))
        assertEquals(null, decorationOf("Compilers"))

        feed = changes(row(1, "cancelled"))
        composeRule.waitForIdle()

        assertTrue(shows(cancelled))
        assertEquals(TextDecoration.LineThrough, decorationOf("Compilers"))
        assertEquals(TextDecoration.LineThrough, decorationOf("09:10 - 12:10"))
    }

    @Test
    fun `a moved class shows its new time and room`() {
        feed = changes(
            row(1, "start", periods = listOf("2", "3", "4"), values = mapOf("start" to "09:40")),
            row(2, "room", periods = listOf("2", "3", "4"), values = mapOf("room" to "TR-313")),
        )
        composeRule.waitForIdle()

        assertTrue(shows("09:40 - 12:10"))
        assertTrue(shows("TR-313", substring = true))
        assertTrue(!shows("T4-501", substring = true))
    }
}
