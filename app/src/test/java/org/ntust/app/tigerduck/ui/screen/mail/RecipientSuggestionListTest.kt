package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The people a recipient field suggests, under it while it is typed in, and what a tap on one does. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class RecipientSuggestionListTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val teacher = MailAddress("王大明", "wang@example.com")

    private var field by mutableStateOf(RecipientField())

    /** The field as the compose screen draws it, suggesting [teacher] for anything typed. */
    private fun show(initial: RecipientField = RecipientField(), modifier: Modifier = Modifier) {
        field = initial
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                MailRecipientTokenField(
                    label = "To",
                    field = field,
                    onChange = { change -> change(field).also { field = it } },
                    enabled = true,
                    modifier = modifier,
                    suggestions = if (field.draft.isBlank()) emptyList() else listOf(teacher),
                )
            }
        }
    }

    @Test
    fun `the field's modifier stays on what is typed into`() {
        // As MailRecipientTokenFieldTest finds the field it types into, by a tag passed in.
        show(modifier = Modifier.testTag("to"))

        composeRule.onNodeWithTag("to").performTextInput("a@x.tw,")

        assertEquals(listOf("a@x.tw"), field.entries)
    }

    @Test
    fun `what is typed shows the people it matches, and a tap on one makes it the bubble`() {
        show()
        composeRule.onNode(hasSetTextAction()).performTextInput("大明")
        composeRule.onNodeWithText("wang@example.com").assertExists()

        composeRule.onNodeWithText("wang@example.com").performClick()

        assertEquals(listOf("王大明 <wang@example.com>"), field.entries)
        assertEquals("", field.draft)
        composeRule.onNodeWithText("wang@example.com").assertDoesNotExist()
    }

    @Test
    fun `nothing is suggested under a field no one is typing in`() {
        show(RecipientField(draft = "大明"))

        composeRule.onNodeWithText("wang@example.com").assertDoesNotExist()
    }
}
