package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.DisplayScale
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Other settings > Display and text size: the app's and the widgets' sliders,
 * each stored as the step it is let go on, the reset, and the class table's
 * course name size, which opens its own page.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
// Tall, so the whole list is laid out.
@Config(application = Application::class, qualifiers = "en-rUS-w411dp-h2400dp")
class DisplaySizeContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var steps = 0
    private var openedCourseNameSize = false
    private val committed = mutableListOf<Pair<DisplayScale, Float>>()

    private fun show(picked: Map<DisplayScale, Float> = emptyMap(), courseNameScale: Float = 1f) =
        composeRule.setContent {
            TigerDuckAppTheme {
                DisplaySizeContent(
                    picked = DisplayScale.entries.associateWith { picked[it] ?: 1f },
                    courseNameScale = courseNameScale,
                    onCommit = { scale, value -> committed += scale to value },
                    onStep = { steps++ },
                    onBack = {},
                    onNavigateToCourseNameSize = { openedCourseNameSize = true },
                )
            }
        }

    /** The [index]th slider named [label]: the app's first, the widgets' second. */
    private fun slider(label: String, index: Int): SemanticsNodeInteraction =
        composeRule.onAllNodes(
            hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        )[index]

    private fun slideTo(node: SemanticsNodeInteraction, step: Int) {
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(step.toFloat()) }
        composeRule.waitForIdle()
    }

    @Test
    fun `the app's display size is stored as the step it is let go on`() {
        show()

        slideTo(slider("Display size", 0), DisplayScale.AppUi.stepIndex(0.85f))

        assertEquals(listOf(DisplayScale.AppUi to 0.85f), committed)
        assertEquals(1, steps)
    }

    @Test
    fun `the widgets' sliders store the widgets' sizes`() {
        show()

        slideTo(slider("Text size", 1), DisplayScale.WidgetText.steps.lastIndex)
        slideTo(slider("Display size", 1), DisplayScale.WidgetUi.stepIndex(1.2f))

        assertEquals(listOf(DisplayScale.WidgetText to 1.5f, DisplayScale.WidgetUi to 1.2f), committed)
    }

    @Test
    fun `a slider reads out its size as a percentage`() {
        show(picked = mapOf(DisplayScale.AppText to 1.3f))

        slider("Text size", 0).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "130%"))
        slider("Display size", 0).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
    }

    @Test
    fun `the reset puts every size back to the phone's own`() {
        show(picked = mapOf(DisplayScale.AppUi to 0.8f, DisplayScale.WidgetText to 1.3f))

        composeRule.onNodeWithText("Reset to default").performClick()
        composeRule.waitForIdle()

        assertEquals(DisplayScale.entries.map { it to 1f }.toSet(), committed.toSet())
    }

    @Test
    fun `the reset does nothing when every size is already the phone's own`() {
        show()

        composeRule.onNodeWithText("Reset to default").performClick()
        composeRule.waitForIdle()

        assertTrue(committed.isEmpty())
    }

    @Test
    fun `the class table's course name size shows its size and opens its page`() {
        show(courseNameScale = 1.2f)

        composeRule.onNodeWithText("1.20×").assertIsDisplayed()
        composeRule.onNodeWithText("Course name size").performClick()

        assertTrue(openedCourseNameSize)
    }

    @Test
    fun `the page has the app's sizes, the widgets' and the class table's`() {
        show()

        listOf("Preview", "In the app", "Widgets", "Class table").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }
}
