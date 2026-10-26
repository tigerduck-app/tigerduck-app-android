package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Signed out, a page offers a labelled sign-in button rather than a lock that happens to be
 * tappable (https://github.com/tigerduck-app/tigerduck-app-android/issues/258).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class SignedOutSignInButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `an empty state with an action shows it as a labelled button`() {
        var taps = 0
        composeRule.setContent {
            TigerDuckAppTheme {
                EmptyStateView(
                    icon = Icons.Filled.Lock,
                    title = "Not signed in",
                    message = null,
                    actionLabel = "Sign in to school system",
                    onAction = { taps++ },
                )
            }
        }

        composeRule.onNodeWithText("Sign in to school system").assertIsDisplayed().performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `Home's assignments, signed out, offer to sign in`() {
        var taps = 0
        composeRule.setContent {
            TigerDuckAppTheme {
                AssignmentsEmptyState(
                    isLoggedIn = false,
                    isLoading = false,
                    filter = AssignmentFilter.INCOMPLETE,
                    onOpenSignInSettings = { taps++ },
                )
            }
        }

        composeRule.onNodeWithText("Sign in to school system").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `Home's time slider, signed out, offers to sign in`() {
        var taps = 0
        // The slider ticks every second for as long as it is shown.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TigerDuckAppTheme {
                TimeSliderSection(
                    courses = emptyList(),
                    quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
                    invertDirection = false,
                    isLoggedIn = false,
                    onSignIn = { taps++ },
                    onSelectCourse = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Sign in to school system").performClick()

        assertEquals(1, taps)
    }
}
