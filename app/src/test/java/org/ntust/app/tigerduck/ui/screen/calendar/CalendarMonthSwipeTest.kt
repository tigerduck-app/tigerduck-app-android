package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The month grid pages with a sideways swipe, as the iPhone's (tigerduck-app#1033): a swipe
 * turns the month and picks no day; the arrows turn one month and Today slides next door and
 * jumps from farther; TalkBack hears the month after a swipe, not after a button, and a button
 * calls off a month still to be said; the grid is as tall as the tallest month on screen.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class CalendarMonthSwipeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val taipei = ZoneId.of("Asia/Taipei")
    private fun month(year: Int, month: Int): Date = Date.from(LocalDate.of(year, month, 1).atStartOfDay(taipei).toInstant())

    private var displayed by mutableStateOf(Date(0))
    private val picked = mutableListOf<Date>()
    private val changed = mutableListOf<Date>()
    private val announced = mutableListOf<String>()

    /** The grid on [start], with the view model's month in [displayed], as the screen holds it. */
    private fun show(start: Date) {
        displayed = start
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                MonthCalendar(
                    displayedMonth = displayed,
                    selectedDate = start,
                    eventsByDay = emptyMap(),
                    onDateSelected = { picked += it },
                    onMonthChanged = { changed += it; displayed = it },
                    announce = { announced += it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val pager get() = composeRule.onNodeWithTag(MONTH_PAGER_TAG)
    private fun title(year: Int, month: Int) =
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.calendar_month_year, year, month))
    private fun arrow(id: Int) = composeRule.onNodeWithContentDescription(composeRule.activity.getString(id))

    /** Past the wait before a month is said, and any turn still moving. */
    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    /** [count] frames, with what each starts run before the next: the clock stands still between them. */
    private fun frames(count: Int) = repeat(count) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
    }

    private val pagerHeightDp: Float
        get() = with(composeRule.density) { pager.fetchSemanticsNode().size.height.toDp().value }

    @Test
    fun `a swipe that starts on a day turns the month and picks no day`() {
        show(month(2026, 7))

        pager.performTouchInput { swipeLeft(startX = centerX, endX = left) }
        settle()

        assertEquals(listOf(month(2026, 8)), changed)
        assertEquals(emptyList<Date>(), picked)
        title(2026, 8).assertExists()
    }

    @Test
    fun `TalkBack hears the month after a swipe, and nothing after the arrows`() {
        show(month(2026, 7))

        arrow(R.string.calendar_next_month).performClick()
        settle()
        assertEquals(listOf(month(2026, 8)), changed)
        assertEquals(emptyList<String>(), announced)

        pager.performTouchInput { swipeLeft(startX = centerX, endX = left) }
        settle()
        assertEquals(listOf(month(2026, 8), month(2026, 9)), changed)
        assertEquals(listOf("September 2026"), announced)
    }

    @Test
    fun `an arrow pressed before the month is said calls it off`() {
        show(month(2026, 7))
        composeRule.mainClock.autoAdvance = false

        pager.performTouchInput { swipeLeft(startX = centerX, endX = left) }
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 3_000) { changed.isNotEmpty() }
        arrow(R.string.calendar_next_month).performClick()
        composeRule.mainClock.advanceTimeBy(2_000)

        assertEquals(listOf(month(2026, 8), month(2026, 9)), changed)
        assertEquals(emptyList<String>(), announced)
    }

    @Test
    fun `an arrow's turn a drag takes back leaves the next swipe said`() {
        show(month(2026, 7))
        composeRule.mainClock.autoAdvance = false

        // Next, then a slow drag back before the turn is half way: the grid rests on July again.
        arrow(R.string.calendar_next_month).performClick()
        frames(2)
        pager.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(5f, 0f)) }
            up()
        }
        composeRule.mainClock.autoAdvance = true
        settle()
        assertEquals(emptyList<Date>(), changed)

        pager.performTouchInput { swipeLeft(startX = centerX, endX = left) }
        settle()
        assertEquals(listOf(month(2026, 8)), changed)
        assertEquals(listOf("August 2026"), announced)
    }

    @Test
    fun `Today jumps from a far month and slides from the one next door, saying nothing`() {
        show(month(2026, 7))
        composeRule.mainClock.autoAdvance = false

        displayed = month(2030, 1)
        frames(3)
        title(2030, 1).assertExists()

        displayed = month(2030, 2)
        frames(1)
        title(2030, 1).assertExists()
        composeRule.mainClock.advanceTimeBy(2_000)
        title(2030, 2).assertExists()

        assertEquals(emptyList<String>(), announced)
    }

    @Test
    fun `the grid opens on its month without passing another`() {
        show(month(2030, 1))

        title(2030, 1).assertExists()
        assertEquals(emptyList<Date>(), changed)
        assertEquals(emptyList<String>(), announced)
    }

    // July 2026 has five weeks from Monday, August six.
    @Test
    fun `the grid is as tall as the tallest month on screen, and holds it through a swipe`() {
        show(month(2026, 7))
        assertEquals(5 * 40f, pagerHeightDp, 0.5f)

        // A slow drag a fifth of the way toward August, which snaps back.
        pager.performTouchInput {
            down(center)
            repeat(16) { moveBy(Offset(-5f, 0f)) }
        }
        composeRule.waitForIdle()
        assertEquals("mid-swipe", 6 * 40f, pagerHeightDp, 0.5f)

        pager.performTouchInput { up() }
        settle()
        assertEquals(emptyList<Date>(), changed)
        assertEquals("back on July", 5 * 40f, pagerHeightDp, 0.5f)
    }

    @Test
    fun `TalkBack hears the month by its name`() {
        assertEquals("December 2026", monthAnnouncement(month(2026, 12), Locale.US))
        assertEquals("2026年12月", monthAnnouncement(month(2026, 12), Locale.TAIWAN))
    }
}
