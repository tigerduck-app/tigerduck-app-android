package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CreditBreakdown
import org.ntust.app.tigerduck.data.model.CreditSummary
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The credit summary at the top of the scores: three totals, kept short so the courses start soon. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class, qualifiers = "en-w400dp-h800dp")
class CreditSummaryCardTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val summary = CreditSummary(
        earned = CreditBreakdown(inPerson = 98f, distance = 2f, total = 100f),
        enrolled = CreditBreakdown(inPerson = 18f, distance = 0f, total = 18f),
        total = CreditBreakdown(inPerson = 116f, distance = 2f, total = 118f),
    )

    @Test
    fun `the credit summary stays short, with all three totals`() {
        composeRule.setContent { TigerDuckAppTheme { CreditSummaryCard(summary) } }

        val height = composeRule.onRoot().getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("the card is $height tall", height <= 175.dp)
        listOf(R.string.score_credit_earned, R.string.score_credit_enrolled, R.string.score_credit_total).forEach {
            composeRule.onNodeWithText(composeRule.activity.getString(it)).assertExists()
        }
        listOf("100", "18", "118").forEach { composeRule.onNodeWithText(it).assertExists() }
    }
}
