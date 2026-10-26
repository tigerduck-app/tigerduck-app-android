package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Someone tapping through What's New as fast as they can keeps hitting the spot where Next is.
// On a page that asks them something, the answer we suggest has to be the one under that spot.
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class, qualifiers = "w384dp-h832dp-xxhdpi")
class WhatsNewDecisionButtonsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(en: String) = WhatsNewText(en = en, zhHant = en)

    private val feature = WhatsNewPage.Feature(
        id = "feature",
        visual = WhatsNewVisual.Icon(Icons.Filled.Mail),
        title = text("A feature"),
        body = text("What it does."),
    )

    private val optIn = WhatsNewPage.OptIn(
        id = "opt-in",
        visual = WhatsNewVisual.Icon(Icons.Filled.Mail),
        title = text("Change the bar?"),
        body = text("We suggest it."),
        confirmLabel = text("Apply suggested"),
        declineLabel = text("Keep mine"),
        apply = {},
    )

    private val permission = WhatsNewPage.Permission(
        id = "permission",
        visual = WhatsNewVisual.Icon(Icons.Filled.Mail),
        title = text("Notify you?"),
        body = text("For new mail."),
        confirmLabel = text("Allow"),
        declineLabel = text("Not now"),
        permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
    )

    private fun open(vararg pages: WhatsNewPage) {
        composeRule.setContent { WhatsNewSheet(WhatsNewFlow(pages = pages.toList(), summary = null), onDismiss = {}) }
        composeRule.waitForIdle()
    }

    private fun node(label: String) = composeRule.onNodeWithText(label)

    private val SemanticsNodeInteraction.bounds get() = fetchSemanticsNode().boundsInWindow

    /** Where Next sits on a feature page, then [label] once Next has moved on to the next page. */
    private fun nextSpotThen(label: String): Pair<Float, Float> {
        val next = node(composeRule.activity.getString(R.string.action_next))
        val nextBottom = next.bounds.bottom
        next.performClick()
        composeRule.waitForIdle()
        return nextBottom to node(label).bounds.bottom
    }

    @Test
    fun `on a question, the suggested answer is where Next was`() {
        open(feature, optIn)

        val (nextBottom, applyBottom) = nextSpotThen("Apply suggested")

        assertEquals(nextBottom, applyBottom, 1f)
        assertTrue("Keep mine sits under the suggestion", node("Keep mine").bounds.top < node("Apply suggested").bounds.top)
    }

    @Test
    fun `on a permission, Allow is where Next was`() {
        open(feature, permission)

        val (nextBottom, allowBottom) = nextSpotThen("Allow")

        assertEquals(nextBottom, allowBottom, 1f)
        assertTrue("Not now sits under Allow", node("Not now").bounds.top < node("Allow").bounds.top)
    }
}
