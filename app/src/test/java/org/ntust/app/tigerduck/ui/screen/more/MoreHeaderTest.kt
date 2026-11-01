package org.ntust.app.tigerduck.ui.screen.more

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderAtTop
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderScrollsAway
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertRowEndsWith
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.title
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lazy lists' header, which Calendar, Empty Classroom and Settings share: the list's first item. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w400dp-h400dp")
class MoreHeaderTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun show() {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) { MoreScreen(rememberNavController()) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the title and Settings show at the top of the list`() {
        show()
        composeRule.title(text(R.string.feature_more)).assertIsDisplayed()
        val settings = composeRule.onNodeWithContentDescription(text(R.string.feature_settings)).assertIsDisplayed()
        composeRule.assertHeaderAtTop(text(R.string.feature_more), density)
        composeRule.assertRowEndsWith(settings, density, targetMargin = 4f)
    }

    @Test
    fun `the header scrolls away with the list`() {
        show()
        composeRule.assertHeaderScrollsAway(text(R.string.feature_more), density)
    }
}
