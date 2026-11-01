package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SubPageScaffoldTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun backLabel() = composeRule.activity.getString(R.string.action_back)

    @Test
    fun `the title and the page content are shown`() {
        composeRule.setContent {
            SubPageScaffold(title = "Storage", onBack = {}) { Text("page body") }
        }
        composeRule.onNodeWithText("Storage").assertIsDisplayed()
        composeRule.onNodeWithText("page body").assertIsDisplayed()
    }

    @Test
    fun `the back arrow calls onBack once`() {
        var backs = 0
        composeRule.setContent { SubPageScaffold(title = "Storage", onBack = { backs++ }) {} }
        composeRule.onNodeWithContentDescription(backLabel()).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `actions are shown in the bar and are clickable`() {
        var clicks = 0
        composeRule.setContent {
            SubPageScaffold(
                title = "Tabs",
                onBack = {},
                actions = { androidx.compose.material3.TextButton(onClick = { clicks++ }) { Text("Reset") } },
            ) {}
        }
        composeRule.onNodeWithText("Reset").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `without actions the bar has only the back arrow and title`() {
        composeRule.setContent { SubPageScaffold(title = "Storage", onBack = {}) {} }
        composeRule.onNodeWithText("Storage").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(backLabel()).assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `the content starts below the compact bar`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            SubPageScaffold(title = "Storage", onBack = {}) { padding = it }
        }
        composeRule.runOnIdle {
            assertEquals(56.dp, padding!!.calculateTopPadding())
        }
    }

    @Test
    fun `contentWindowInsets reaches the Scaffold`() {
        var padding: PaddingValues? = null
        composeRule.setContent {
            SubPageScaffold(
                title = "Storage",
                onBack = {},
                contentWindowInsets = WindowInsets(bottom = 10.dp),
            ) { padding = it }
        }
        composeRule.runOnIdle { assertEquals(10.dp, padding!!.calculateBottomPadding()) }
    }
}
