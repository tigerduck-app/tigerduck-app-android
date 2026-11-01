package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A pushed Moodle screen's bar offers the page on the site only once it knows that page. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class OpenInMoodleActionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val label get() = composeRule.activity.getString(R.string.action_open_in_moodle)

    @Test
    fun `a screen with no page on the site has no button`() {
        composeRule.setContent {
            PageScaffold(title = "Forum", onBack = {}, actions = { OpenInMoodleAction(null) }) {}
        }
        composeRule.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test
    fun `a screen with a page offers it, saying so first to whatever it would stop`() {
        var told = 0
        composeRule.setContent {
            PageScaffold(
                title = "Forum",
                onBack = {},
                actions = { OpenInMoodleAction("/mod/forum/view.php?id=1", willOpenPage = { told++ }) },
            ) {}
        }
        composeRule.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        assertEquals(1, told)
    }
}
