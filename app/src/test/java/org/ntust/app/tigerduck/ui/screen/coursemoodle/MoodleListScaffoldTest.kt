package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A course's Moodle list has a ⋮ with Settings next to Open in Moodle, as iOS's. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleListScaffoldTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `the menu follows Open in Moodle and opens the settings Moodle's rows sit in`() {
        val opened = mutableListOf<PageSettings>()
        composeRule.setContent {
            CompositionLocalProvider(LocalOpenPageSettings provides { opened += it }) {
                MoodleListScaffold(title = "Forums", onBack = {}, moodlePath = "/mod/forum/index.php?id=1") {}
            }
        }
        val open = composeRule.onNodeWithContentDescription(text(R.string.action_open_in_moodle)).assertIsDisplayed()
        val menu = composeRule.onNodeWithContentDescription(text(R.string.action_more_actions)).assertIsDisplayed()
        assertTrue(open.fetchSemanticsNode().boundsInRoot.right <= menu.fetchSemanticsNode().boundsInRoot.left + 1f)

        menu.performClick()
        composeRule.onNodeWithText(text(R.string.action_settings)).performClick()
        assertEquals(listOf(PageSettings.MOODLE), opened)
    }
}
