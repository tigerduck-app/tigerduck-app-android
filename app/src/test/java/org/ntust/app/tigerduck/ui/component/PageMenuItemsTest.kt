package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pieces every page's ⋮ is built from: the Settings item and a switch. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PageMenuItemsTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val asked = mutableListOf<String>()

    private fun show(items: @androidx.compose.runtime.Composable (close: () -> Unit) -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalOpenPageSettings provides { asked += "settings of $it" }) {
                TigerDuckAppTheme(darkTheme = false) {
                    PageScaffold(
                        title = "Page",
                        menu = PageMenu(label = "More actions") { close ->
                            items {
                                asked += "close"
                                close()
                            }
                        },
                    ) {}
                }
            }
        }
        composeRule.onNodeWithContentDescription("More actions").performClick()
    }

    @Test
    fun `Settings closes the menu, then opens the page's own settings`() {
        show { close -> PageSettingsItem(PageSettings.MAIL, close) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_settings)).performClick()

        assertEquals(listOf("close", "settings of ${PageSettings.MAIL}"), asked)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_settings)).assertDoesNotExist()
    }

    @Test
    fun `a switch reads as one, with its state, and a tap asks for the other state`() {
        var turned: Boolean? = null
        show { PageMenuSwitch("Show full due date", checked = true, onCheckedChange = { turned = it }) }

        composeRule.onNodeWithText("Show full due date")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn()
            .performClick()

        assertEquals(false, turned)
    }

    @Test
    fun `a greyed-out switch shows its state and turns nothing`() {
        var turned: Boolean? = null
        show { PageMenuSwitch("New mail notifications", checked = false, onCheckedChange = { turned = it }, enabled = false) }

        composeRule.onNodeWithText("New mail notifications").assertIsOff().assertIsNotEnabled().performClick()

        assertEquals(null, turned)
    }
}
