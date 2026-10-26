package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EmptyStateViewActionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a failure state offers its action under the message`() {
        var retried = 0
        composeRule.setContent {
            EmptyStateView(Icons.Filled.Mail, "Couldn't load mail", "Network error", actionLabel = "Retry", onAction = { retried++ })
        }
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `without an action there is no button`() {
        composeRule.setContent { EmptyStateView(Icons.Filled.Mail, "No mail", null, actionLabel = "Retry") }
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }
}
