package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric's Magnifier has no surface to show or dismiss, and a long press into a selection
 * brings one up: these do nothing instead, which is all the selection itself needs.
 */
@Implements(Magnifier::class)
class NoOpMagnifierShadow {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [NoOpMagnifierShadow::class])
class MessageHeaderSelectionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Selection handles are popups of their own: the window's root plus one per handle. */
    private fun assertRoots(count: Int) = composeRule.onAllNodes(isRoot()).assertCountEquals(count)

    @Test
    fun `a long press on the sender's address selects it, so it can be copied`() {
        composeRule.setContent { SenderAddress("office@mail.ntust.edu.tw") }
        assertRoots(1)
        composeRule.onNodeWithText("office@mail.ntust.edu.tw").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertRoots(3)
    }

    @Test
    fun `plain text outside a selection container selects nothing, so the check above means something`() {
        composeRule.setContent { Text("office@mail.ntust.edu.tw") }
        composeRule.onNodeWithText("office@mail.ntust.edu.tw").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertRoots(1)
    }
}
