package org.ntust.app.tigerduck.ui.screen.score

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.GpaTrendPoint
import org.ntust.app.tigerduck.data.model.RankingStats
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack reads for the GPA trend chart on the score screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class RankingsTrendA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun point(term: String, gpa: Double, isProvisional: Boolean = false) = GpaTrendPoint(
        term = term,
        semester = RankingStats(gpa = gpa),
        cumulative = RankingStats(gpa = gpa),
        isProvisional = isProvisional,
    )

    private fun show() {
        composeRule.setContent {
            RankingsTrendCard(
                points = listOf(point("1131", 3.5), point("1132", 3.8), point("1141", 4.0, isProvisional = true)),
                scope = ScoreViewModel.RankingScope.SEMESTER,
                onScopeChange = {},
            )
        }
    }

    private fun stateOf(label: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label)

    @Test
    fun `the chart is named and reads out the latest term until one is picked`() {
        show()

        composeRule.onNodeWithContentDescription("GPA trend by semester")
            .assert(stateOf("114-1 GPA 4.00 · Provisional"))
    }

    @Test
    fun `each term is read with its GPA and can be picked`() {
        show()

        composeRule.onNodeWithContentDescription("113-1 GPA 3.50")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .performClick()

        composeRule.onNodeWithContentDescription("113-1 GPA 3.50").assertIsSelected()
        composeRule.onNodeWithContentDescription("GPA trend by semester").assert(stateOf("113-1 GPA 3.50"))
    }
}
