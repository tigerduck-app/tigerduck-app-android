package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.appicon.AppIcon
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The App icon page: each icon a choice TalkBack reads by name, the pick marked, as on the iPhone. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AppIconChoicesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val choice = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun title(icon: AppIcon) = composeRule.activity.getString(icon.title)

    @Test
    fun `every icon is a choice in the iPhone's order, named, with the pick marked`() {
        var chosen by mutableStateOf(AppIcon.DEFAULT)
        val picks = mutableListOf<AppIcon>()
        composeRule.setContent { AppIconChoices(chosen) { picks += it; chosen = it } }

        val choices = composeRule.onAllNodes(isSelectable())
        choices.assertCountEquals(AppIcon.entries.size)
        AppIcon.entries.forEachIndexed { i, icon -> choices[i].assert(choice).assert(hasText(title(icon))) }
        choices[0].assertIsSelected()
        choices[1].assertIsNotSelected()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.app_icon_name_tigerduck_christmas)).performClick()

        assertEquals(listOf(AppIcon.TIGER_DUCK_CHRISTMAS), picks)
        choices[3].assertIsSelected()
        choices[0].assertIsNotSelected()
    }
}
