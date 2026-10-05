package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.ViewMode
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme

/**
 * The message's ⋮ menu as TalkBack reads it. Its radio buttons and checkbox take no clicks of their
 * own, so they expose no state; each menu item carries its role and state instead. A later change to
 * the menu that dropped them would still look right on screen, which is why this reads the semantics.
 */
@RunWith(AndroidJUnit4::class)
class MailMessageMenuTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lightMode = context.getString(R.string.school_mail_view_light_mode)
    private val formatted = context.getString(R.string.school_mail_view_formatted)
    private val plain = context.getString(R.string.school_mail_view_plain)
    private val source = context.getString(R.string.school_mail_view_source)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** The menu held open, with light mode toggled by its own item the way the screen does. */
    private fun show(mode: ViewMode = ViewMode.FORMATTED, offersLightMode: Boolean = true) {
        rule.setContent {
            var viewInLight by remember { mutableStateOf(false) }
            TigerDuckAppTheme(darkTheme = true) {
                MessageMenu(
                    expanded = true,
                    mode = mode,
                    canFormat = true,
                    offersLightMode = offersLightMode,
                    viewInLight = viewInLight,
                    onDismiss = {},
                    onMode = {},
                    onViewInLight = { viewInLight = !viewInLight },
                    onMarkUnread = {},
                    onMove = {},
                    onDelete = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun lightModeIsACheckboxThatSaysWhetherItIsOn() {
        show()
        rule.onNodeWithText(lightMode).assert(hasRole(Role.Checkbox)).assertIsOff()
        rule.onNodeWithText(lightMode).performClick()
        rule.onNodeWithText(lightMode).assertIsOn()
        rule.onNodeWithText(lightMode).performClick()
        rule.onNodeWithText(lightMode).assertIsOff()
    }

    @Test
    fun theViewModesAreRadioButtonsThatSayWhichIsSelected() {
        show(mode = ViewMode.PLAIN, offersLightMode = false)
        rule.onNodeWithText(plain).assert(hasRole(Role.RadioButton)).assertIsSelected()
        rule.onNodeWithText(formatted).assert(hasRole(Role.RadioButton)).assertIsNotSelected()
        rule.onNodeWithText(source).assert(hasRole(Role.RadioButton)).assertIsNotSelected()
    }

    @Test
    fun lightModeIsLeftOutWhereItIsNotOffered() {
        show(mode = ViewMode.PLAIN, offersLightMode = false)
        rule.onNodeWithText(lightMode).assertDoesNotExist()
    }
}
