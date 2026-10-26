package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AboutOthersLinksTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show() = composeRule.setContent {
        TigerDuckAppTheme {
            AboutOthersContent(
                browserPreference = "system",
                onBack = {},
                onNavigateToSourceCode = {},
                onNavigateToLicenses = {},
            )
        }
    }

    private fun tap(label: Int): String? {
        composeRule.onNodeWithText(composeRule.activity.getString(label)).performClick()
        return shadowOf(composeRule.activity).nextStartedActivity?.dataString
    }

    @Test
    fun `privacy policy opens the page onboarding, TigerSync and iOS open`() {
        show()
        // app.ntust.org, which this row used to open, does not resolve.
        assertEquals("https://tigerduck.app/privacy-policy", tap(R.string.settings_privacy_policy))
    }

    @Test
    fun `delete account opens the deletion page`() {
        show()
        assertEquals("https://tigerduck.app/delete-account", tap(R.string.settings_delete_account))
    }

    @Test
    fun `a link on a phone with no browser does nothing rather than crash`() {
        // Otherwise Robolectric "starts" any intent, whether or not an activity would handle it.
        shadowOf(composeRule.activity.application).checkActivities(true)
        show()
        assertNull(tap(R.string.settings_privacy_policy))
    }
}
