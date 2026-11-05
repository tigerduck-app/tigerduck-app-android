package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
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
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.at
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.course
import org.ntust.app.tigerduck.ui.screen.home.FakeHomeClubs.Companion.club
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's card for a club's activity, at 19:30 on the fixture's Thursday. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class TimeSliderClubCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun freezeClock() {
        AppClock.setOverride(ClockOverride(at("19:30").toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun show(slider: TimeSliderViewModel, courses: List<Course> = listOf(course())) {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                TimeSliderSection(
                    courses = courses,
                    quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
                    invertDirection = false,
                    viewModel = slider,
                    onSelectCourse = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun hint() = composeRule.activity.getString(R.string.a11y_club_activity_open_details_hint)

    // The row measures its cards once to find the tallest and once to place them, so each shows twice.
    private fun card(description: String) = composeRule.onAllNodesWithContentDescription(description).onFirst()

    private fun shows(text: String) = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `the card's headline is the activity's title, over the club, the location and the whole time`() {
        show(TimeSliderViewModel(FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00")))))

        card("Practice, 測試社團0 · TR-000, 19:00 - 21:00. ${hint()}").assertExists()
        listOf("Practice", "測試社團0 · TR-000", "19:00 - 21:00").forEach { assertTrue("$it is shown", shows(it) > 0) }
    }

    @Test
    fun `with no title the club's name is the headline, and the club is not named twice`() {
        show(TimeSliderViewModel(FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00", title = "")))))

        card("測試社團0, TR-000, 19:00 - 21:00. ${hint()}").assertExists()
        assertTrue(shows("測試社團0") > 0)
        assertEquals("the club's name once for each time the location is", shows("TR-000"), shows("測試社團0"))
        assertEquals(0, shows("測試社團0 · TR-000"))
    }

    @Test
    fun `the card shows the whole activity, where the track shows what a class left of it`() {
        show(TimeSliderViewModel(FakeHomeClubs(listOf(club(1, 0, "15:00", "21:00")))), courses = listOf(course()))

        // The afternoon class (to 16:20) cuts the track to 16:20 to 21:00; the card gives the whole 15:00 to 21:00.
        card("Practice, 測試社團0 · TR-000, 15:00 - 21:00. ${hint()}").assertExists()
    }

    @Test
    fun `a tap on the card opens the club's details`() {
        val occurrence = club(1, 0, "19:00", "21:00")
        val slider = TimeSliderViewModel(FakeHomeClubs(listOf(occurrence)))
        show(slider)
        val location = composeRule.activity.getString(R.string.class_table_club_location_label)
        assertEquals(0, composeRule.onAllNodesWithText(location).fetchSemanticsNodes().size)

        composeRule.onAllNodes(hasContentDescription("Practice", substring = true)).onFirst().performClick()
        composeRule.waitForIdle()

        assertEquals(occurrence, slider.openedClub)
        assertTrue(composeRule.onAllNodesWithText(location).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a day with only club activities shows the time machine, not the empty state`() {
        show(TimeSliderViewModel(FakeHomeClubs(listOf(club(1, 0, "19:00", "21:00")))), courses = emptyList())

        card("Practice, 測試社團0 · TR-000, 19:00 - 21:00. ${hint()}").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.home_time_slider_no_courses)).fetchSemanticsNodes().size)
    }
}
