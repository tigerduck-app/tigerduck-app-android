package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TigerSync status row after the automatic sign-in failed
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/203).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class SignInAgainRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `it says the automatic sign-in failed and opens the sign-in`() {
        var opened = 0
        composeRule.setContent {
            TigerDuckAppTheme { SignInAgainRow(onSignInAgain = { opened++ }) }
        }

        composeRule.onNodeWithText("Auto sign-in failed").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in again").performClick()

        assertEquals(1, opened)
    }
}
