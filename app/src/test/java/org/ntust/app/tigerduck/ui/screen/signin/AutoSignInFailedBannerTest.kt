package org.ntust.app.tigerduck.ui.screen.signin

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Automatic sign-in failed" banner on Home, the class table and Score
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/257).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
class AutoSignInFailedBannerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `it shows while the school refuses the stored password`() {
        val rejection = StoredPasswordLatch.Rejection(null)

        assertSame(rejection, bannerRejection(rejection, dismissed = null, signedIn = true))
    }

    @Test
    fun `it stays away with nothing refused`() {
        assertNull(bannerRejection(null, dismissed = null, signedIn = true))
    }

    @Test
    fun `it shows only while signed in`() {
        assertNull(bannerRejection(StoredPasswordLatch.Rejection(null), dismissed = null, signedIn = false))
    }

    @Test
    fun `closed, it stays closed for that refusal, and shows again for the next`() {
        val closed = StoredPasswordLatch.Rejection("Locked")

        assertNull(bannerRejection(closed, dismissed = closed, signedIn = true))
        val next = StoredPasswordLatch.Rejection("Locked")
        assertSame(next, bannerRejection(next, dismissed = closed, signedIn = true))
    }

    @Test
    fun `it says what failed and why, and offers to sign in again or close`() {
        var signInAgain = 0
        var closed = 0
        composeRule.setContent {
            TigerDuckAppTheme {
                AutoSignInFailedCard(
                    reason = "Sign-in failed: Wrong account or password",
                    onSignInAgain = { signInAgain++ },
                    onDismiss = { closed++ },
                )
            }
        }

        composeRule.onNodeWithText("Auto sign-in failed").assertIsDisplayed()
        composeRule.onNodeWithText("Sign-in failed: Wrong account or password").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in again").performClick()
        composeRule.onNodeWithContentDescription("Close").performClick()

        assertEquals(1, signInAgain)
        assertEquals(1, closed)
    }
}
