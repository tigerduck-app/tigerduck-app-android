package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
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

    private val links = mutableListOf<SettingsLink>()

    private fun show() = composeRule.setContent {
        TigerDuckAppTheme { Column { AboutOthersRows(browserPreference = "system") { links += it } } }
    }

    private fun top(label: Int) = composeRule.onNodeWithText(composeRule.activity.getString(label)).fetchSemanticsNode().boundsInRoot.top

    private fun tap(label: Int): String? {
        composeRule.onNodeWithText(composeRule.activity.getString(label)).performClick()
        return shadowOf(composeRule.activity).nextStartedActivity?.dataString
    }

    @Test
    fun `server status comes first, then the project's links, in order`() {
        show()
        val rows = listOf(
            R.string.settings_check_server_status, R.string.settings_feedback_bug_report, R.string.settings_privacy_policy,
            R.string.settings_delete_account, R.string.settings_open_source_licenses, R.string.settings_view_source_code,
        )
        assertTrue(rows.zipWithNext().all { (a, b) -> top(a) < top(b) })
    }

    @Test
    fun `server status opens the status page`() {
        show()
        assertEquals("https://status.tigerduck.app/", tap(R.string.settings_check_server_status))
    }

    @Test
    fun `the licenses and the source code open their pages`() {
        show()
        tap(R.string.settings_open_source_licenses)
        tap(R.string.settings_view_source_code)
        assertEquals(listOf(SettingsLink.LICENSES, SettingsLink.SOURCE_CODE), links)
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
