package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R

/**
 * The compose screen's To field on screen: typing finishes bubbles, leaving the field or Next
 * finishes the last one, and a bubble opens again where it stands. The field is held the way the
 * view model holds it, every change applied to the latest copy.
 */
@RunWith(AndroidJUnit4::class)
class MailRecipientTokenFieldTest {

    @get:Rule
    val rule = createComposeRule()

    private val state = mutableStateOf(RecipientField())
    private val field: RecipientField get() = state.value

    private val removeLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_remove)

    private fun show(initial: RecipientField = RecipientField()) {
        state.value = initial
        rule.setContent {
            MaterialTheme {
                Column {
                    MailRecipientTokenField(
                        "To",
                        state.value,
                        { change -> change(state.value).also { state.value = it } },
                        enabled = true,
                        modifier = Modifier.testTag(TO),
                    )
                    // Somewhere for focus to go.
                    OutlinedTextField("", {}, label = { Text("Subject") }, modifier = Modifier.testTag(SUBJECT))
                }
            }
        }
    }

    private fun texts() = rule.runOnIdle { field.tokens.map { it.text } }

    @Test
    fun aCommaFinishesARecipient() {
        show()
        rule.onNodeWithTag(TO).performTextInput("a@x.tw,")
        assertEquals(listOf("a@x.tw"), texts())
        rule.runOnIdle { assertEquals("", field.draft) }
        rule.onNodeWithContentDescription("a@x.tw").assertExists()
    }

    @Test
    fun leavingTheFieldFinishesWhatWasTyped() {
        show()
        rule.onNodeWithTag(TO).performTextInput("a@x.tw")
        rule.runOnIdle { assertEquals("a@x.tw", field.draft) }
        rule.onNodeWithTag(SUBJECT).performClick()
        assertEquals(listOf("a@x.tw"), texts())
        rule.runOnIdle { assertEquals("", field.draft) }
    }

    @Test
    fun nextFinishesWhatWasTyped() {
        show()
        rule.onNodeWithTag(TO).performTextInput("Bob <b@x.tw>")
        rule.onNodeWithTag(TO).performImeAction()
        assertEquals(listOf("Bob <b@x.tw>"), texts())
    }

    @Test
    fun aTappedBubbleOpensInItsPlaceAndGoesBackThere() {
        show(RecipientField.of("a@x.tw, b@x.tw, c@x.tw"))
        rule.onNodeWithContentDescription("b@x.tw").performClick()
        rule.runOnIdle {
            assertEquals("b@x.tw", field.draft)
            assertEquals(listOf("a@x.tw", "c@x.tw"), field.tokens.map { it.text })
            assertEquals(listOf("a@x.tw", "b@x.tw", "c@x.tw"), field.entries)
        }
        rule.onNodeWithTag(TO).assert(hasText("b@x.tw"))
        rule.onNodeWithContentDescription("b@x.tw").assertDoesNotExist()

        rule.onNodeWithTag(SUBJECT).performClick()
        assertEquals(listOf("a@x.tw", "b@x.tw", "c@x.tw"), texts())
    }

    @Test
    fun theCrossRemovesOnlyItsOwnBubble() {
        show(RecipientField.of("a@x.tw, b@x.tw"))
        rule.onNodeWithContentDescription("$removeLabel a@x.tw").performClick()
        assertEquals(listOf("b@x.tw"), texts())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backspaceWithNothingTypedOpensTheBubbleBeforeIt() {
        show(RecipientField.of("a@x.tw, b@x.tw"))
        rule.onNodeWithTag(TO).requestFocus()
        rule.onNodeWithTag(TO).performKeyInput { pressKey(Key.Backspace) }
        rule.runOnIdle {
            assertEquals("b@x.tw", field.draft)
            assertEquals(listOf("a@x.tw"), field.tokens.map { it.text })
        }
    }

    private companion object {
        const val TO = "to"
        const val SUBJECT = "subject"
    }
}
