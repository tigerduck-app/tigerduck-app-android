package org.ntust.app.tigerduck.wear.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.wear.compose.material3.MaterialTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.wear.ui.theme.WatchCardStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

/** A Now & Next card, as the iPhone's `ClassCard`: caption with the range, name, room alone, countdown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en")
class ClassCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val monday = LocalDate.of(2026, 11, 2)
    private val course = Course.fromSchedule("DB", "Databases", instructor = "Ada", classroom = "TR-412", schedule = mapOf(1 to listOf("3", "4")))

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
        // 11:15 Taipei, 55 minutes into the class.
        AppClock.setOverride(ClockOverride(Instant.parse("2026-11-02T03:15:00Z").toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun tearDown() = AppClock.setOverride(null)

    private fun card(headline: String, change: WatchClassChange? = null, room: String = "TR-412", countdownOn: LocalDate? = null) =
        rule.setContent {
            MaterialTheme {
                ClassCard(headline, course, 10 * 60 + 20, 12 * 60 + 10, room, change, WatchCardStyle.Tint, countdownOn)
            }
        }

    @Test
    fun `the class going on shows its range, its room alone and the time left`() {
        card("NOW", countdownOn = monday)
        rule.waitForIdle()
        rule.onNodeWithText("NOW · 10:20–12:10").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("NOW, 10:20 to 12:10")))
        rule.onNodeWithText("Databases").assertExists()
        rule.onNodeWithText("TR-412").assertExists()
        rule.onNodeWithText("55:00").assertExists()
        rule.onNodeWithText("Ada", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a class shown next has its range and no countdown`() {
        card("Tomorrow")
        rule.waitForIdle()
        rule.onNodeWithText("Tomorrow · 10:20–12:10").assertExists()
        rule.onNodeWithTag(COUNTDOWN_TAG).assertDoesNotExist()
    }

    @Test
    fun `an online class shows Online as a moved room`() {
        card("NEXT", change = WatchClassChange(listOf("3", "4"), room = "Online"), room = "Online")
        rule.waitForIdle()
        rule.onNodeWithText("⇄ Online").assertExists()
    }
}
