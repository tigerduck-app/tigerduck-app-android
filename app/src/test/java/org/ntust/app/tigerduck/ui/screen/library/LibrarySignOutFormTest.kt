package org.ntust.app.tigerduck.ui.screen.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After a library sign-out the sign-in form starts over: no password is left to reveal or sign in
 * with, and the ID is prefilled as a fresh page's is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class LibrarySignOutFormTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var signedIn by mutableStateOf(false)
    private lateinit var shown: LibraryLoginFields
    private fun text(id: Int) = composeRule.activity.getString(id)

    /** The page as the library's session drives it: the form signed out, the menu signed in. */
    private fun show() {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                val fields = rememberLibraryLoginFields(signedIn, storedUsername = null) { "B00015999" }
                shown = fields
                PageScaffold(
                    title = "Library",
                    menu = libraryMenu(signedIn, flipSupported = false, flipOn = { false }, onFlip = {}, onSignOut = { signedIn = false }),
                ) {
                    if (!signedIn) {
                        LoginPromptCard(
                            username = fields.username,
                            password = fields.password,
                            isLoggingIn = false,
                            onUsernameChange = { fields.username = it },
                            onPasswordChange = { fields.password = it },
                            onSubmit = {},
                        )
                    }
                }
            }
        }
    }

    private fun field(label: Int) = composeRule.onNode(hasSetTextAction() and hasText(text(label)))

    private fun holds(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))

    @Test
    fun `signed out from the menu, the form has no password to reveal and the ID a fresh page's`() {
        show()
        assertEquals("B00015999", shown.username)
        field(R.string.library_sign_in_password).performTextInput("not-a-real-password")
        // The library takes the sign-in.
        composeRule.runOnIdle { signedIn = true }

        composeRule.onNodeWithContentDescription(text(R.string.action_more_actions)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_library_sign_out)).performClick()
        composeRule.waitForIdle()

        // Empty, there is nothing for the eye button to reveal. Not pressed here: revealing turns the
        // screen secure, which a debug build asks the app's Hilt graph about, and this app has none.
        field(R.string.library_sign_in_password).assert(holds(""))
        assertEquals("", shown.password)
        assertEquals("the ID is prefilled as a fresh page's", "B00015999", shown.username)
    }
}
