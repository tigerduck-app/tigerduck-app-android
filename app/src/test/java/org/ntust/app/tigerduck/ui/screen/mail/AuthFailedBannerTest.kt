package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthFailedBannerTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `the banner has a labelled close button beside Sign in again`() {
        var closed = 0
        var signIns = 0
        composeRule.setContent { AuthFailedBanner(onSignInAgain = { signIns++ }, onClose = { closed++ }) }
        composeRule.onNodeWithContentDescription(text(R.string.action_close)).performClick()
        assertEquals(1, closed)
        composeRule.onNodeWithText(text(R.string.action_sign_in_again)).performClick()
        assertEquals(1, signIns)
    }
}
