package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The root's new sub-pages: Notifications › Notification switches and settings, whose rows lead to
 * the pages each notification is set on, and Other settings › Other settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h2000dp")
class SettingsSubPagesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private val links = mutableListOf<SettingsLink>()
    private val pages = mutableListOf<PageSettings>()

    private fun tap(id: Int) {
        composeRule.onNodeWithText(text(id)).performClick()
        composeRule.waitForIdle()
    }

    private fun top(id: Int) = composeRule.onNodeWithText(text(id)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `each notification's row opens the page it is set on, in order`() {
        composeRule.setContent {
            Column { NotificationSwitchRows(SettingsState(schoolSignedIn = true, mailSignedIn = true), { links += it }) { pages += it } }
        }

        val titles = listOf(
            R.string.live_activity_channel_name, R.string.settings_assignment_due_reminder, R.string.settings_class_reminder,
            R.string.bulletin_notifications_title, R.string.school_mail_settings_notifications,
        )
        assertTrue(titles.zipWithNext().all { (a, b) -> top(a) < top(b) })
        titles.forEach(::tap)

        assertEquals(
            listOf(SettingsLink.LIVE_ACTIVITY, SettingsLink.ASSIGNMENT_REMINDERS, SettingsLink.CLASS_REMINDERS, SettingsLink.MAIL_NOTIFICATIONS),
            links,
        )
        assertEquals(listOf(PageSettings.BULLETINS), pages)
    }

    @Test
    fun `signed out, the bulletins' and the mail's rows are dimmed and say why, as on their pages`() {
        var state by mutableStateOf(SettingsState())
        composeRule.setContent { Column { NotificationSwitchRows(state, { links += it }) { pages += it } } }

        tap(R.string.bulletin_notifications_title)
        assertEquals(text(R.string.settings_gate_school_sign_in), ShadowToast.getTextOfLatestToast())
        tap(R.string.school_mail_settings_notifications)
        assertEquals(text(R.string.settings_gate_mail_sign_in), ShadowToast.getTextOfLatestToast())
        assertTrue(links.isEmpty() && pages.isEmpty())

        // The reminders and Live Updates need no sign-in.
        tap(R.string.settings_class_reminder)
        assertEquals(listOf(SettingsLink.CLASS_REMINDERS), links)

        composeRule.runOnIdle { state = SettingsState(schoolSignedIn = true) }
        tap(R.string.bulletin_notifications_title)
        assertEquals(listOf(PageSettings.BULLETINS), pages)
    }

    @Test
    fun `Other settings holds the API endpoint, vibration, screen rotation and usage analytics, in order`() {
        var analytics by mutableStateOf(false)
        composeRule.setContent {
            Column {
                OtherSettingsRows(
                    rotationMode = "auto",
                    onRotationMode = {},
                    analyticsEnabled = analytics,
                    onAnalyticsEnabled = { analytics = it },
                    open = { links += it },
                )
            }
        }

        val titles = listOf(
            R.string.settings_api_endpoint, R.string.vibration_settings_title, R.string.settings_screen_rotation,
            R.string.settings_analytics_enabled, R.string.settings_analytics_description,
        )
        assertTrue(titles.zipWithNext().all { (a, b) -> top(a) < top(b) })
        composeRule.onNodeWithText(text(R.string.settings_analytics_enabled)).assertIsOff()

        tap(R.string.settings_api_endpoint)
        tap(R.string.vibration_settings_title)
        tap(R.string.settings_analytics_enabled)

        assertEquals(listOf(SettingsLink.API_ENDPOINT, SettingsLink.VIBRATION), links)
        assertTrue(analytics)
    }

    @Test
    fun `a section's title on Settings is larger than other screens', 24dp under the card before it`() {
        val account = text(R.string.settings_section_account)
        val appearance = text(R.string.settings_section_appearance)
        composeRule.setContent {
            TigerDuckAppTheme {
                SettingsList(
                    Modifier, SettingsState(), openPage = {}, open = {},
                    header = { Text("Settings") },
                ) { part ->
                    when (part) {
                        RootPart.ACCOUNT -> SettingsSectionCard(SettingsSection.ACCOUNT) { Text("account row") }
                        RootPart.APPEARANCE -> SettingsSectionCard(SettingsSection.APPEARANCE) {
                            Text("appearance row")
                        }
                        else -> Unit
                    }
                }
            }
        }

        val layout = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(appearance).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        assertEquals(24f, layout.single().layoutInput.style.fontSize.value, 0f)

        val density = composeRule.density.density
        fun bounds(label: String) = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertEquals(24f, (bounds(appearance).top - bounds("account row").bottom) / density, 0.5f)
        assertEquals(8f, (bounds("account row").top - bounds(account).bottom) / density, 0.5f)
    }
}
