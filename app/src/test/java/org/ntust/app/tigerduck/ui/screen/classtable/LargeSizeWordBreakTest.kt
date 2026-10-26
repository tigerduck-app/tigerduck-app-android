// Lays the course sheet's top cards and the rename dialog's default row out with real text
// (native graphics) at the app's largest sizes on a phone-width screen, and reads back where
// each line ended, so a word that breaks in the middle with no hyphen fails here rather than
// on a phone with the app's display and text size turned up.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import android.icu.text.BreakIterator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The emulator the bug was seen on is 411dp wide; the app's display size at 130% makes that
 * 316dp, and its text size at 150% a font scale of 1.5. A 316dp screen at 1.5 lays text out in
 * the same proportions, which is what decides where a line breaks.
 */
private const val LargestTextScale = 1.5f

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w316dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeSizeWordBreakTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val course = Course(courseNo = "CS3001301", courseName = "Operating Systems")

    @Test
    fun `the course sheet's cards break no word in the middle at the largest sizes`() {
        showSheet(fontScale = LargestTextScale)

        assertBreaksBetweenWords(text("Classroom"))
        assertBreaksBetweenWords(text("Time"))
        assertBreaksBetweenWords(text("TR-313"))
        assertBreaksBetweenWords(text("09:10 - 12:00"))
        // Room, not hyphens: the cards go one under the other.
        assertTrue("the cards are still side by side", bounds("Time").top > bounds("Classroom").bottom)
    }

    @Test
    fun `the course sheet's cards stay side by side at the usual size`() {
        showSheet(fontScale = 1f)

        val classroom = bounds("Classroom")
        val time = bounds("Time")
        assertEquals("the cards are no longer in a row", classroom.top, time.top, 0.5f)
        assertTrue("the cards are no longer side by side", time.left > classroom.right)
    }

    @Test
    @Config(qualifiers = "zh-rTW-w316dp-h1200dp")
    fun `the course sheet's cards stay side by side in Chinese at the largest sizes`() {
        showSheet(fontScale = LargestTextScale)

        val classroom = bounds("教室")
        val time = bounds("時間")
        assertEquals("the cards are no longer in a row", classroom.top, time.top, 0.5f)
        assertTrue("the cards are no longer side by side", time.left > classroom.right)
    }

    @Test
    fun `the rename dialog's default name breaks no word in the middle at the largest sizes`() {
        showRenameRow(defaultName = "Operating Systems", fontScale = LargestTextScale)

        assertBreaksBetweenWords(text("Default: Operating Systems"))
        // Room, not hyphens: the button goes under the name.
        assertTrue(
            "the button is still beside the name",
            bounds("Revert to default").top >= bounds("Default: Operating Systems").bottom,
        )
    }

    @Test
    fun `the rename dialog keeps its default name beside the button at the usual size`() {
        showRenameRow(defaultName = "Operating Systems", fontScale = 1f)

        val label = bounds("Default: Operating Systems")
        val button = bounds("Revert to default")
        assertTrue("the default name moved off the button's row", label.top < button.bottom && button.top < label.bottom)
        assertTrue("the default name is not beside the button", button.left > label.right)
    }

    @Test
    fun `a name that fits its lines whole is not hyphenated`() {
        showRenameRow(defaultName = "Operating Systems", fontScale = LargestTextScale)

        assertNotEquals(Hyphens.Auto, text("Default: Operating Systems").layoutInput.style.hyphens)
    }

    private fun showSheet(fontScale: Float) {
        composeRule.setContent {
            Scaled(fontScale) {
                TigerDuckAppTheme(darkTheme = false) {
                    // As CourseDetailSheetPage frames it.
                    Box {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            CourseDetailContent(
                                course = course,
                                title = course.courseName,
                                classroom = "TR-313",
                                timeRange = "09:10 - 12:00",
                                assignments = emptyList(),
                                onRename = {},
                                onOpenInMoodle = null,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The row in the rename dialog it is drawn in, so it gets the dialog's own width. */
    private fun showRenameRow(defaultName: String, fontScale: Float) {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                TigerDuckDialog(onDismissRequest = {}, title = "Rename", confirmText = "OK", dismissText = "Cancel") {
                    // The dialog is a window of its own, which provides its own density.
                    Scaled(fontScale) {
                        CourseRenameDefaultRow(defaultName = defaultName, canRevert = true, onRevert = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }

    private fun textNodes(): List<SemanticsNode> = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()

    private fun node(text: String): SemanticsNode = textNodes().single { it.text() == text }

    private fun text(text: String): TextLayoutResult = node(text).textLayout()

    private fun bounds(text: String): Rect = node(text).boundsInRoot

    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text)!!.joinToString()

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /**
     * Every line but the last ends between words, without hyphens: at these sizes every one of
     * these words has room for itself.
     */
    private fun assertBreaksBetweenWords(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        assertNotEquals("\"$text\" is hyphenated", Hyphens.Auto, layout.layoutInput.style.hyphens)
        val breaks = BreakIterator.getLineInstance(java.util.Locale.ENGLISH).apply { setText(text) }
        (0 until layout.lineCount - 1).forEach { line ->
            val end = layout.getLineEnd(line)
            assertTrue(
                "\"$text\" breaks inside a word: \"${text.substring(0, end)}\" / \"${text.substring(end)}\"",
                breaks.isBoundary(end),
            )
        }
    }
}
