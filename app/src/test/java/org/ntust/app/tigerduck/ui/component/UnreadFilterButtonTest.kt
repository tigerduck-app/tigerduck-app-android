package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The unread filter Bulletins and School Mail share: a tap filters, a long press marks all read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UnreadFilterButtonTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var unreadOnly by mutableStateOf(false)
    private var marked = 0

    private fun show(anyUnread: Boolean) {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                UnreadFilterButton(
                    unreadOnly = unreadOnly,
                    label = "Unread only",
                    markAllReadLabel = "Mark all as read",
                    onUnreadOnlyChange = { unreadOnly = it },
                    onMarkAllRead = if (anyUnread) ({ marked++ }) else null,
                )
            }
        }
    }

    private fun filter() = composeRule.onNodeWithContentDescription("Unread only")

    @Test
    fun `a tap shows unread only, and a second everything again, saying which`() {
        show(anyUnread = true)
        filter().assertIsOff().performClick()
        filter().assertIsOn()
        assertTrue(unreadOnly)
        filter().performClick()
        filter().assertIsOff()
    }

    @Test
    fun `a long press offers Mark all as read, which marks and leaves the filter as it was`() {
        show(anyUnread = true)
        filter().performTouchInput { longClick() }
        composeRule.onNodeWithText("Mark all as read").performClick()

        assertEquals(1, marked)
        assertEquals(false, unreadOnly)
        composeRule.onNodeWithText("Mark all as read").assertDoesNotExist()
    }

    @Test
    fun `with nothing shown unread, Mark all as read is greyed out and TalkBack is not offered it`() {
        show(anyUnread = false)
        filter().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        filter().performTouchInput { longClick() }
        composeRule.onNodeWithText("Mark all as read").assertIsNotEnabled()
    }

    @Test
    fun `TalkBack offers Mark all as read as an action of its own`() {
        show(anyUnread = true)
        val actions = filter().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Mark all as read"), actions.map { it.label })
        actions.single().action()
        assertEquals(1, marked)
        filter().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Unread only")))
    }
}
