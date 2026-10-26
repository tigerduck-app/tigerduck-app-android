package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailRow
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What a mail's card on the list does when it is swiped, pressed and held, or acted on by TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SwipeableMailCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /** Every callback the card made, in order. */
    private val calls = mutableListOf<String>()

    /** What the card is shown with; a test changes either while the card stays composed. */
    private var writing by mutableStateOf(false)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private fun show(writing: Boolean = false, direction: LayoutDirection = LayoutDirection.Ltr) {
        this.writing = writing
        this.direction = direction
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides this.direction) {
                SwipeableMailCard(
                    row = MailRow("INBOX", mailSummary(1)),
                    mailDomain = "mail.ntust.edu.tw",
                    writing = this.writing,
                    onClick = { calls += "open" },
                    onToggleRead = { calls += "read" },
                    onMove = { calls += "move" },
                    onDelete = { calls += "delete" },
                )
            }
        }
    }

    private fun card(): SemanticsNodeInteraction = composeRule.onNodeWithText("subject 1")

    private fun customActions(): List<String> =
        card().fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    /** Runs the card's custom accessibility action [label], as TalkBack's actions menu does. */
    private fun performAction(label: String) {
        val action = card().fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        composeRule.runOnIdle { action.action() }
    }

    /** Swipes the card, lets the swipe's fling-out run, and gives back what it called. */
    private fun swipe(towardRight: Boolean): List<String> {
        calls.clear()
        card().performTouchInput { if (towardRight) swipeRight() else swipeLeft() }
        composeRule.waitForIdle()
        return calls.toList()
    }

    /** Presses and holds the card, then taps the item [label] names in the menu that opens. */
    private fun pickFromMenu(@StringRes label: Int): List<String> {
        calls.clear()
        card().performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(label)).performClick()
        composeRule.waitForIdle()
        return calls.toList()
    }

    @Test
    fun `in a left-to-right layout the leading swipe toggles read and the trailing one asks to delete`() {
        show()

        assertEquals(listOf("read"), swipe(towardRight = true))
        assertEquals(listOf("delete"), swipe(towardRight = false))
    }

    @Test
    fun `in a right-to-left layout the swipes are mirrored`() {
        show(direction = LayoutDirection.Rtl)

        assertEquals(listOf("read"), swipe(towardRight = false))
        assertEquals(listOf("delete"), swipe(towardRight = true))
    }

    @Test
    fun `a card that stays on screen through a change of layout direction swipes the new way`() {
        show()
        assertEquals(listOf("read"), swipe(towardRight = true))

        // The app's language changes to a right-to-left one, which the activity takes without
        // being recreated.
        direction = LayoutDirection.Rtl
        composeRule.waitForIdle()

        assertEquals(listOf("read"), swipe(towardRight = false))
        assertEquals(listOf("delete"), swipe(towardRight = true))
    }

    @Test
    fun `a card held for its write in the middle of a swipe is back in place when it is given back`() {
        show()
        val left = card().fetchSemanticsNode().boundsInRoot.left
        // Partway through a swipe, short of the threshold, the card's write starts.
        card().performTouchInput {
            down(center)
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(-60f, 0f))
        }
        composeRule.waitForIdle()
        assertTrue("the swipe never moved the card", card().fetchSemanticsNode().boundsInRoot.left < left)
        writing = true
        composeRule.waitForIdle()
        writing = false
        composeRule.waitForIdle()
        card().performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals("the card came back half swiped", left, card().fetchSemanticsNode().boundsInRoot.left)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a tap opens the mail`() {
        show()

        card().performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `each item of the long press's menu does what it says`() {
        show()

        assertEquals(listOf("read"), pickFromMenu(R.string.school_mail_mark_read))
        assertEquals(listOf("move"), pickFromMenu(R.string.school_mail_move_to))
        assertEquals(listOf("delete"), pickFromMenu(R.string.school_mail_delete))
        composeRule.onNodeWithText(text(R.string.school_mail_move_to)).assertDoesNotExist()
    }

    @Test
    fun `TalkBack says what the long press opens, on a card it calls a button`() {
        show()

        val config = card().fetchSemanticsNode().config
        assertEquals(text(R.string.school_mail_message_actions), config[SemanticsActions.OnLongClick].label)
        assertEquals(Role.Button, config.getOrElseNullable(SemanticsProperties.Role) { null })
    }

    @Test
    fun `TalkBack can mark the mail read and delete it, which only a swipe does otherwise`() {
        show()

        assertEquals(listOf(text(R.string.school_mail_mark_read), text(R.string.school_mail_delete)), customActions())
        performAction(text(R.string.school_mail_delete))
        assertEquals(listOf("delete"), calls)
        calls.clear()
        performAction(text(R.string.school_mail_mark_read))
        assertEquals(listOf("read"), calls)
    }

    @Test
    fun `a card whose delete or move is still out offers nothing more`() {
        show(writing = true)

        card().assertIsNotEnabled()
        assertTrue("TalkBack can still act on it", customActions().isEmpty())
        card().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        card().performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        card().performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.school_mail_move_to)).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }
}
