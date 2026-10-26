package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class SearchClearButtonTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val clear get() = RuntimeEnvironment.getApplication().getString(R.string.action_clear_text)

    @Test
    fun `the clear button shows while there is text and empties the field`() {
        var text = ""
        composeRule.setContent {
            var value by remember { mutableStateOf("期中考") }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; text = it },
                trailingIcon = searchClearIcon(value) { value = ""; text = "" },
            )
        }
        composeRule.onNodeWithContentDescription(clear).performClick()
        assertEquals("", text)
        composeRule.onNodeWithContentDescription(clear).assertDoesNotExist()
    }
}
