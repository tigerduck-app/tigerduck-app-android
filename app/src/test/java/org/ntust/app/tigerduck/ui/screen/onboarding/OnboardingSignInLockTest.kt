package org.ntust.app.tigerduck.ui.screen.onboarding

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A running onboarding sign-in cannot be stopped, so the wizard cannot leave its page meanwhile,
 * as the Settings sign-in sheet cannot be closed (tigerduck-app-android#351).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class OnboardingSignInLockTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun showActions(isSignedIn: Boolean, isLoggingIn: Boolean) {
        composeRule.setContent {
            TigerDuckAppTheme {
                SignInPageActions(
                    isSignedIn = isSignedIn,
                    isLoggingIn = isLoggingIn,
                    canSignIn = true,
                    onCheckServerStatus = {},
                    onCustomEndpoint = {},
                    onSkip = {},
                    onSignIn = {},
                    onNext = {},
                )
            }
        }
    }

    @Test
    fun `skip is off while a sign-in runs`() {
        showActions(isSignedIn = false, isLoggingIn = true)

        composeRule.onNodeWithText("Skip for now").assertIsNotEnabled()
    }

    @Test
    fun `skip is on with no sign-in running`() {
        showActions(isSignedIn = false, isLoggingIn = false)

        composeRule.onNodeWithText("Skip for now").assertIsEnabled()
    }

    @Test
    fun `next is off while a sign-in that has signed in is still finishing`() {
        showActions(isSignedIn = true, isLoggingIn = true)

        composeRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun `neither arrow nor system back leaves the page while a sign-in runs`() {
        var isLoggingIn by mutableStateOf(true)
        val moves = mutableListOf<Int>()
        var exits = 0
        composeRule.setContent {
            TigerDuckAppTheme {
                Box {
                    OnboardingStepNavigation(
                        page = 4,
                        canAdvance = true,
                        isLoggingIn = isLoggingIn,
                        onGoToPage = { moves += it },
                        onBackOnFirstPage = { exits++ },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next").assertIsNotEnabled()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(emptyList<Int>(), moves)
        assertEquals(0, exits)
        assertEquals("system back stayed in the wizard", false, composeRule.activity.isFinishing)

        isLoggingIn = false
        composeRule.onNodeWithContentDescription("Back").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Next").assertIsEnabled()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(listOf(3), moves)
    }
}
