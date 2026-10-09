// Lays the More screen's cards out with real text (native graphics) at the
// widths and font sizes that used to cut a name's second line off, and reads
// back what each name's layout did, so a card that clips its name again fails
// here rather than on a phone with its font turned up.

package org.ntust.app.tigerduck.ui.screen.more

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureGridTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val features = AppFeature.moreFeatures

    @Test
    fun `cards keep their shape when every name fits one line`() {
        show(width = 600.dp, fontScale = 1f)

        val cards = cards()
        val cellWidth = cards.first().width
        cards.forEach { assertEquals(cellWidth / 1.6f, it.height, 1f) }
        labels().forEach { (name, label) -> assertEquals("$name wrapped", 1, label.layout.lineCount) }
    }

    @Test
    fun `a larger font grows every card alike and cuts no name off`() {
        show(width = 360.dp, fontScale = 1.3f)

        val cards = cards()
        labels().forEach { (name, label) ->
            assertFitsItsCard(name, label, cards)
            assertFalse("$name is cut short", label.layout.isLineEllipsized(label.layout.lineCount - 1))
        }
        cards.forEach { assertEquals(cards.first().height, it.height, 0.5f) }
        assertTrue("the cards did not grow", cards.first().height > cards.first().width / 1.6f + 1f)
    }

    @Test
    fun `a word too long for its line is set smaller rather than broken`() {
        // At 24sp "Announcements" is wider than a 360dp phone's card, and
        // Android would break it as "Announcemen" / "ts".
        show(width = 360.dp, fontScale = 1f)

        val layout = labels().getValue("Announcements").layout
        // layoutInput keeps the style's 24sp; the paragraph is the fitted one.
        val input = layout.layoutInput
        val at24sp = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
            .measure(input.text, input.style)
        assertTrue("fits at 24sp anyway", at24sp.size.width > input.constraints.maxWidth)
        assertEquals(1, layout.lineCount)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `a name that cannot fit even at the smallest size stays inside its card`() {
        // "Университетская почта": the first word alone outgrows the card at 2x.
        show(width = 360.dp, fontScale = 2f)

        val cards = cards()
        labels().forEach { (name, label) -> assertFitsItsCard(name, label, cards) }
    }

    private fun show(width: Dp, fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TigerDuckAppTheme(darkTheme = false) {
                    // As MoreScreen wires it, every feature in one grid.
                    BoxWithConstraints(Modifier.requiredWidth(width)) {
                        val columns = featureGridColumns(maxWidth)
                        FeatureGrid(
                            features = features,
                            columns = columns,
                            cardSize = rememberFeatureCardSize(
                                labels = features.map { stringResource(it.displayNameRes) },
                                gridWidthPx = constraints.maxWidth,
                                columns = columns,
                            ),
                            implementedFeatures = emptySet(),
                            navController = rememberNavController(),
                            onNotImplemented = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun cards(): List<Rect> = composeRule.onAllNodes(hasClickAction())
        .fetchSemanticsNodes()
        .map { it.boundsInRoot }
        .also { assertEquals(features.size, it.size) }

    private class Label(val bounds: Rect, val layout: TextLayoutResult)

    private fun labels(): Map<String, Label> = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .associate { node -> node.text() to Label(node.boundsInRoot, node.textLayout()) }
        .also { assertEquals(features.size, it.size) }

    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text)!!.joinToString()

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /**
     * A Text's bounds never exceed the room it is given, even when its lines
     * do, so the lines are checked against the Text and the Text against its card.
     */
    private fun assertFitsItsCard(name: String, label: Label, cards: List<Rect>) {
        val layout = label.layout
        assertTrue("$name has more than two lines", layout.lineCount <= FeatureLabelMaxLines)
        assertTrue(
            "$name runs below its box",
            layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 0.5f,
        )
        assertTrue("$name runs out of its card", cards.any { card -> card.contains(label.bounds) })
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left - 0.5f && other.top >= top - 0.5f &&
            other.right <= right + 0.5f && other.bottom <= bottom + 0.5f
}
