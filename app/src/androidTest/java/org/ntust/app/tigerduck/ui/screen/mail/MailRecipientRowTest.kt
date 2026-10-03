package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme

/**
 * The read screen's To and Cc lines on screen. The label and the recipients beside it share a
 * baseline whatever script each is in: a line holding Chinese characters is taller than one of
 * Latin letters alone, since the fallback font's ascent and descent widen it, so lining the two
 * up by their tops put "收件者" visibly below a bare address and "To" visibly above a Chinese name.
 */
@RunWith(AndroidJUnit4::class)
class MailRecipientRowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(label: String, recipients: List<MailRecipient>) {
        rule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                MailRecipientRow(label, recipients)
            }
        }
        rule.waitForIdle()
    }

    /** The baseline of the first line of the text containing [text], in the root's pixels. */
    private fun baselineOf(text: String): Float {
        val node = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("'$text' has no text layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        return node.boundsInRoot.top + results.single().firstBaseline
    }

    private fun assertAligned(label: String, recipient: String) {
        assertEquals("'$label' and '$recipient' sit on different baselines", baselineOf(label), baselineOf(recipient), 1f)
    }

    @Test
    fun chineseLabelSharesBaselineWithLatinAddress() {
        show("收件者", listOf(MailRecipient(null, "b12345678@mail.ntust.edu.tw", isSelf = true)))
        assertAligned("收件者", "b12345678@")
    }

    @Test
    fun latinLabelSharesBaselineWithChineseName() {
        show("To", listOf(MailRecipient("王大明", "b12345678@mail.ntust.edu.tw", isSelf = false)))
        assertAligned("To", "王大明")
    }

    @Test
    fun openLineKeepsLabelAndFirstRecipientOnOneBaseline() {
        show(
            "收件者",
            listOf(
                MailRecipient(null, "b12345678@mail.ntust.edu.tw", isSelf = true),
                MailRecipient(null, "lee@mail.ntust.edu.tw", isSelf = false),
            ),
        )
        assertAligned("收件者", "b12345678@")
        rule.onNodeWithText("收件者").performClick()
        rule.waitForIdle()
        assertAligned("收件者", "b12345678@")
    }
}
