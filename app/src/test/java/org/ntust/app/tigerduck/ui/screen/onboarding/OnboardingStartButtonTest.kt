package org.ntust.app.tigerduck.ui.screen.onboarding

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en")
// Real text measuring, so a line break is a line break.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingStartButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A 360dp phone's page: the page pads 32dp on each side. */
    private val pageWidth = 296.dp

    private fun show(label: String) {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.width(pageWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                    OnboardingStartButton(label = label, onClick = {})
                }
            }
        }
    }

    private fun lines(label: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.single().lineCount
    }

    @Test
    fun `Start using TigerDuck stays on one line, the button widening for it`() {
        show("Start using TigerDuck")

        assertEquals(1, lines("Start using TigerDuck"))
    }

    @Test
    fun `a short label keeps the button at least 60 percent of the page`() {
        show("開始使用")

        val bounds = composeRule.onNode(hasClickAction()).getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue("button is $width wide", width >= pageWidth * 0.6f)
    }

    @Test
    fun `a label longer than the page still wraps rather than being cut`() {
        val long = "Ξεκινήστε να χρησιμοποιείτε το TigerDuck σήμερα κιόλας"
        show(long)

        assertTrue(lines(long) > 1)
    }
}
