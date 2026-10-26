package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.BrowserPreference
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A link in a bulletin's body opens through openLink, as the Source button does: a web page the
 * way "Open links with" says, and nothing for a scheme that would start some other app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BulletinBodyLinksTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun tap(markdown: String, label: String, browserPreference: String = BrowserPreference.SYSTEM): Intent? {
        composeRule.setContent { MaterialTheme { BodyMarkdown(markdown, browserPreference) } }
        // The markdown is parsed off the main thread.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(label, substring = true).performClick()
        return shadowOf(composeRule.activity).nextStartedActivity
    }

    @Test
    fun `a link to another app's scheme starts nothing`() {
        assertNull(tap("[Rate us](market://details?id=com.example.other)", "Rate us"))
    }

    @Test
    fun `a web link opens in the default browser when the setting says so`() {
        val intent = tap("[Registry](https://www.ntust.edu.tw/)", "Registry")
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals("https://www.ntust.edu.tw/", intent?.dataString)
    }

    @Test
    fun `a web link opens in an in-app tab when the setting says so`() {
        val intent = tap("[Registry](https://www.ntust.edu.tw/)", "Registry", BrowserPreference.IN_APP)
        assertTrue(intent?.hasExtra(CustomTabsIntent.EXTRA_SESSION) == true)
    }
}
