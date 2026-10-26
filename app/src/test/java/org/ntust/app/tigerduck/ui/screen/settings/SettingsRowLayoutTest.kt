// Lays settings rows out with real text (native graphics) at the font size
// that broke their labels mid-word and cut their values off, and reads back
// what each text's layout did.

package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRowLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a value too wide to sit beside its label goes under it, and neither is broken or cut`() {
        show(width = 360.dp, fontScale = 1.3f) {
            SettingsLinkRowWithValue(label = "Language", value = "Follow system (Fallback English)", onClick = {})
        }

        assertWholeAndInsideTheRow("Language", "Follow system (Fallback English)")
        val value = text("Follow system (Fallback English)").bounds
        assertTrue("the value is not under the label", value.top >= text("Language").bounds.bottom - 0.5f)
    }

    @Test
    fun `a picker's label and value are neither broken nor cut at a large font`() {
        show(width = 240.dp, fontScale = 1.3f) {
            SettingsPickerRow(
                label = "Open links with",
                value = "System default browser",
                options = listOf("system" to "System default browser"),
                selectedKey = "system",
                onSelect = {},
            )
        }

        assertWholeAndInsideTheRow("Open links with", "System default browser")
    }

    @Test
    fun `a value that fits stays beside its label`() {
        show(width = 360.dp, fontScale = 1f) {
            SettingsLinkRowWithValue(label = "Language", value = "English", onClick = {})
        }

        val label = text("Language").bounds
        val value = text("English").bounds
        assertTrue("the value is not beside the label", value.left >= label.right)
        assertTrue("the value is not on the label's line", value.top < label.bottom && value.bottom > label.top)
    }

    @Test
    fun `a TigerSync link row is one button the size of the row, its label included`() {
        var taps = 0
        show(width = 360.dp, fontScale = 1f) { LinkRow(label = "Privacy policy", onClick = { taps++ }) }

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onAllNodes(hasClickAction())[0]
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasText("Privacy policy"))
        val row = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().single()
        assertTrue("shorter than a touch target", row.size.height >= with(row.layoutInfo.density) { 48.dp.toPx() })

        composeRule.onNodeWithText("Privacy policy").performClick()
        assertEquals(1, taps)
    }

    private fun show(width: Dp, fontScale: Float, row: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TigerDuckAppTheme(darkTheme = false) {
                    Box(Modifier.requiredWidth(width)) { row() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private class ShownText(val bounds: Rect, val layout: TextLayoutResult)

    private fun text(text: String): ShownText = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .single { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() == text }
        .let { ShownText(it.boundsInRoot, it.textLayout()) }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun assertWholeAndInsideTheRow(vararg texts: String) {
        val row = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().single().boundsInRoot
        for (shown in texts) {
            val (bounds, layout) = text(shown).let { it.bounds to it.layout }
            // A Text's bounds never exceed the room it is given, even when its lines do, so the
            // lines are checked against the Text and the Text against its row.
            val last = layout.lineCount - 1
            assertFalse("\"$shown\" is cut short", layout.isLineEllipsized(last))
            assertTrue("\"$shown\" runs below its box", layout.getLineBottom(last) <= layout.size.height + 0.5f)
            for (line in 0..last) {
                assertTrue("\"$shown\" runs past its box", layout.getLineRight(line) <= layout.size.width + 0.5f)
            }
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertTrue(
                    "\"$shown\" breaks mid-word after \"${shown.substring(0, end)}\"",
                    shown[end - 1].isWhitespace() || shown.getOrNull(end)?.isWhitespace() == true,
                )
            }
            assertTrue("\"$shown\" runs out of its row", row.contains(bounds))
        }
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left - 0.5f && other.top >= top - 0.5f &&
            other.right <= right + 0.5f && other.bottom <= bottom + 0.5f
}
