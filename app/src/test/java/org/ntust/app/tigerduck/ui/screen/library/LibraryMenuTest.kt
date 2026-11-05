package org.ntust.app.tigerduck.ui.screen.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The library's ⋮, as iOS's: Flip to show QR, Settings, then Sign out of library, signed in only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class LibraryMenuTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val picked = mutableListOf<String>()
    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun show(signedIn: Boolean, flipSupported: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalOpenPageSettings provides { picked += "settings of $it" }) {
                TigerDuckAppTheme(darkTheme = false) {
                    PageScaffold(
                        title = "Library",
                        menu = libraryMenu(
                            signedIn = signedIn,
                            flipSupported = flipSupported,
                            flipOn = { true },
                            onFlip = { picked += "flip $it" },
                            onSignOut = { picked += "sign out" },
                        ),
                    ) {}
                }
            }
        }
    }

    private fun menuButton() = composeRule.onNodeWithContentDescription(text(R.string.action_more_actions))

    private fun flip() = composeRule.onNode(hasText(text(R.string.settings_library_flip_to_show_qr)) and isToggleable())

    @Test
    fun `signed out of the library, there is nothing to change and no menu`() {
        show(signedIn = false)
        menuButton().assertDoesNotExist()
    }

    @Test
    fun `signed in, the flip, Settings and the sign-out, in that order`() {
        show(signedIn = true)
        menuButton().performClick()
        val tops = listOf(
            flip(),
            composeRule.onNodeWithText(text(R.string.action_settings)),
            composeRule.onNodeWithText(text(R.string.settings_library_sign_out)),
        ).map { it.fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `each item does what Settings does, and the menu closes`() {
        show(signedIn = true)
        menuButton().performClick()
        flip().assertIsOn().performClick()
        menuButton().performClick()
        composeRule.onNodeWithText(text(R.string.action_settings)).performClick()
        menuButton().performClick()
        composeRule.onNodeWithText(text(R.string.settings_library_sign_out)).performClick()

        assertEquals(listOf("flip false", "settings of LIBRARY", "sign out"), picked)
        composeRule.onNodeWithText(text(R.string.settings_library_sign_out)).assertDoesNotExist()
    }

    @Test
    fun `a phone that cannot sense the flip has only the sign-out, as Settings has no library page there`() {
        show(signedIn = true, flipSupported = false)
        menuButton().performClick()
        flip().assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_settings)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.settings_library_sign_out)).performClick()
        assertEquals(listOf("sign out"), picked)
    }
}
