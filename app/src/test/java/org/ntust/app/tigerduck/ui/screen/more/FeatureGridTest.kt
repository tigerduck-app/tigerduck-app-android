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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
// A plain Application: the real one is a Hilt app these tests do not need. A tall window, so
// every row of cards is laid out on screen at the largest font.
@Config(application = Application::class, qualifiers = "h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureGridTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val features = AppFeature.moreFeatures

    @Test
    fun `cards keep their shape when every name fits one line`() {
        // Wide enough for "Empty classroom", the longest English name, on one line.
        show(width = 1000.dp, fontScale = 1f)

        val cards = cards()
        val cellWidth = cards.first().width
        cards.forEach { assertEquals(cellWidth / 1.6f, it.height, 1f) }
        labels().forEach { (name, label) ->
            assertEquals("$name wrapped", 1, label.layout.lineCount)
            assertNotEquals("$name is hyphenated", Hyphens.Auto, label.layout.layoutInput.style.hyphens)
        }
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
        assertSameSize(labels())
    }

    @Test
    fun `a word too long for its line is hyphenated at the size every card uses`() {
        // At 24sp "Announcements" is wider than a 360dp phone's card.
        show(width = 360.dp, fontScale = 1f)

        val labels = labels()
        val layout = labels.getValue("Announcements").layout
        val input = layout.layoutInput
        val oneLine = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection)
            .measure(input.text, input.style.copy(fontSize = 24.sp))
        assertTrue("fits its line anyway", oneLine.size.width > input.constraints.maxWidth)
        assertSameSize(labels)
        assertEquals(24.sp, input.style.fontSize)
        assertEquals(Hyphens.Auto, input.style.hyphens)
        assertEquals("en", input.style.localeList?.first()?.language)
        assertEquals(2, layout.lineCount)
        // At its hyphenation point, "Announce-" / "ments", not where the line ran out.
        assertEquals("Announce", input.text.text.substring(0, layout.getLineEnd(0)))
    }

    @Test
    @Config(qualifiers = "+en")
    fun `an English app on a Chinese phone hyphenates in English, though the default locale is the phone's`() {
        // Below API 33 the app's own language changes its resources, not the default locale, which
        // stays the phone's: hyphenation must follow the language the names are in. The test sets
        // that default itself, and nothing on the way reads the SDK level, so it runs on the default.
        val phone = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.TAIWAN)
        try {
            show(width = 360.dp, fontScale = 1f)

            val layout = labels().getValue("Announcements").layout
            val input = layout.layoutInput
            assertEquals("en", input.style.localeList?.first()?.language)
            assertEquals(Hyphens.Auto, input.style.hyphens)
            assertEquals(2, layout.lineCount)
            assertEquals("Announce", input.text.text.substring(0, layout.getLineEnd(0)))
        } finally {
            java.util.Locale.setDefault(phone)
        }
    }

    @Test
    @Config(qualifiers = "+zh-rTW")
    fun `names in Chinese keep one size and break as before`() {
        show(width = 360.dp, fontScale = 1.3f)

        val cards = cards()
        val labels = labels()
        labels.forEach { (name, label) ->
            assertFitsItsCard(name, label, cards)
            assertFalse("$name is cut short", label.layout.isLineEllipsized(label.layout.lineCount - 1))
            assertEquals("zh", label.layout.layoutInput.style.localeList?.first()?.language)
        }
        assertSameSize(labels)
    }

    @Test
    @Config(qualifiers = "+ru")
    fun `a name too long even for its three lines stays inside its card`() {
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

    /** Every name is set at the one size, none smaller to fit its card. */
    private fun assertSameSize(labels: Map<String, Label>) {
        val sizes = labels.mapValues { (_, label) -> label.layout.layoutInput.style.fontSize }
        assertEquals("names set apart: $sizes", 1, sizes.values.distinct().size)
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left - 0.5f && other.top >= top - 0.5f &&
            other.right <= right + 0.5f && other.bottom <= bottom + 0.5f
}
