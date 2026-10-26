package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchFieldIconTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val spinner = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo,
        ProgressBarRangeInfo.Indeterminate,
    )
    private val searchingLabel get() = RuntimeEnvironment.getApplication().getString(R.string.add_course_searching)

    private fun showField(searching: () -> Boolean) {
        composeRule.setContent {
            OutlinedTextField(
                value = "期中考",
                onValueChange = {},
                leadingIcon = { SearchFieldIcon(searching = searching()) },
            )
        }
    }

    @Test
    fun `at rest the field shows the magnifier`() {
        showField { false }
        composeRule.onNode(spinner).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(searchingLabel).assertDoesNotExist()
    }

    @Test
    fun `a search turns the magnifier into a spinner, and its end turns it back`() {
        val searching = mutableStateOf(false)
        showField { searching.value }
        composeRule.onNode(spinner).assertDoesNotExist()

        searching.value = true
        composeRule.waitForIdle()
        composeRule.onNode(spinner).assertExists()

        searching.value = false
        composeRule.waitForIdle()
        composeRule.onNode(spinner).assertDoesNotExist()
    }

    @Test
    fun `the spinner says it is searching as it appears`() {
        showField { true }
        composeRule.onNodeWithContentDescription(searchingLabel)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
