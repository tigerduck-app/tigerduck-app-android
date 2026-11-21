package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A row its gate closes (tigerduck-app#939): it keeps its value, dimmed, and a tap, or TalkBack's
 * double tap, says what it needs in a toast and changes nothing. Its page closes once nothing on
 * it is left to show, and search opens what it finds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsGatedRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val needsLibrary = context.getString(R.string.settings_gate_library_sign_in)

    private fun clickLabel(text: String): String? = composeRule.onNodeWithText(text).fetchSemanticsNode()
        .config.getOrNull(SemanticsActions.OnClick)?.label

    @Test
    fun `a closed switch keeps its value, and a tap says why instead of turning it`() {
        var checked by mutableStateOf(true)
        composeRule.setContent {
            SettingsToggleRow(label = "Flip", checked = checked, closedReason = needsLibrary, onCheckedChange = { checked = it })
        }

        composeRule.onNodeWithText("Flip").performClick()

        assertEquals(true, checked)
        composeRule.onAllNodes(isToggleable())[0].assertIsOn()
        assertEquals(needsLibrary, ShadowToast.getTextOfLatestToast())
        // TalkBack's "double tap to …", where Android reads a hint.
        assertEquals(needsLibrary, clickLabel("Flip"))
    }

    @Test
    fun `a closed link opens nothing and says why`() {
        var opened = 0
        composeRule.setContent { SettingsLinkRow("Notifications", closedReason = needsLibrary) { opened++ } }

        composeRule.onNodeWithText("Notifications").performClick()

        assertEquals(0, opened)
        assertEquals(needsLibrary, ShadowToast.getTextOfLatestToast())
        assertEquals(needsLibrary, clickLabel("Notifications"))
    }

    @Test
    fun `an open row works as ever, its gate opening live`() {
        var reason by mutableStateOf<String?>(needsLibrary)
        var opened = 0
        composeRule.setContent { SettingsLinkRow("Notifications", closedReason = reason) { opened++ } }

        composeRule.runOnIdle { reason = null }
        composeRule.onNodeWithText("Notifications").performClick()

        assertEquals(1, opened)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertNull(clickLabel("Notifications"))
    }

    @Test
    fun `a page closes once its last row goes, and only then`() {
        // A phone that cannot sense the flip: the library's page holds only its sign-out.
        var state by mutableStateOf(SettingsState(librarySignedIn = true))
        var closes = 0
        composeRule.setContent { ClosesWhenEmpty(PageSettings.LIBRARY, state) { closes++ } }
        composeRule.waitForIdle()
        assertEquals(0, closes)

        composeRule.runOnIdle { state = state.copy(librarySignedIn = false) }
        composeRule.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun `a result on a page opens it, and one on the root goes back to it`() {
        val opened = mutableListOf<PageSettings>()
        val revealed = mutableListOf<SettingsRow>()
        val showClassroom = context.getString(R.string.settings_class_table_show_classroom)
        val appIcon = context.getString(R.string.settings_app_icon)
        var query by mutableStateOf(showClassroom)
        composeRule.setContent { SettingsSearchResults(query, SettingsState(), { opened += it }) { revealed += it } }

        composeRule.onNodeWithText("${context.getString(R.string.feature_class_table)} › $showClassroom").performClick()
        composeRule.runOnIdle { query = appIcon }
        composeRule.onNodeWithText("${context.getString(R.string.settings_section_other_settings)} › $appIcon").performClick()

        assertEquals(listOf(PageSettings.CLASS_TABLE), opened)
        assertEquals(listOf(SettingsRow.APP_ICON), revealed)
    }

    @Test
    fun `a result on the root clears the search and scrolls the list to its section`() {
        val appIcon = context.getString(R.string.settings_app_icon)
        composeRule.setContent {
            SettingsList(Modifier, SettingsState(), openPage = {}, header = { Text("Settings") }, initialQuery = appIcon) { part ->
                // Each part taller than the screen, so only a scroll shows the one asked for.
                Text(part.name, Modifier.height(800.dp))
            }
        }

        composeRule.onNodeWithText("${context.getString(R.string.settings_section_other_settings)} › $appIcon").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(RootPart.OTHER.name).assertIsDisplayed()
        composeRule.onNodeWithText(RootPart.ACCOUNT.name).assertDoesNotExist()
    }

    @Test
    fun `a closed row reads as dimmed in its colors, not as a disabled node TalkBack cannot act on`() {
        composeRule.setContent { SettingsLinkRow("Notifications", closedReason = needsLibrary) {} }

        composeRule.onNodeWithText("Notifications").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
    }
}
