package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.notify.MailMutePreset
import org.ntust.app.tigerduck.mail.notify.MailMuteRule
import org.ntust.app.tigerduck.mail.notify.MailMuteSettings
import org.ntust.app.tigerduck.mail.notify.MailQuietHours
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.util.Locale

/** The quiet hours and mute rule sections of the School Mail notifications page (#354). */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class SchoolMailMuteSettingsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val days = mutableListOf<DayOfWeek>()
    private val presets = mutableListOf<Pair<MailMutePreset, Boolean>>()
    private val saved = mutableListOf<MailMuteRule>()
    private val deleted = mutableListOf<String>()
    private var quietOn: Boolean? = null

    private val actions = MailMuteActions(
        setQuietHoursEnabled = { quietOn = it },
        setStart = {},
        setEnd = {},
        toggleDay = { days += it },
        setPreset = { preset, on -> presets += preset to on },
        saveRule = { saved += it },
        deleteRule = { deleted += it },
    )

    private fun show(settings: MailMuteSettings, enabled: Boolean = true) = composeRule.setContent {
        TigerDuckAppTheme(darkTheme = false) {
            LazyColumn { mailMuteItems(settings, enabled, actions) }
        }
    }

    private fun inDialog(text: String) = composeRule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    @Test
    fun `quiet hours show their times and days only while on`() {
        var settings by mutableStateOf(MailMuteSettings())
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) { LazyColumn { mailMuteItems(settings, true, actions) } }
        }
        composeRule.onNodeWithText("Start").assertDoesNotExist()
        // The section's header and its switch, as on the iPhone.
        composeRule.onAllNodesWithText("Quiet hours").assertCountEquals(2)

        settings = MailMuteSettings(quietHours = MailQuietHours(enabled = true))
        composeRule.onNodeWithText("Start").assertExists()
        composeRule.onNodeWithText("End").assertExists()
        composeRule.onNodeWithText("Every day").assertExists()

        composeRule.onNodeWithText("Sat").performClick()
        assertEquals(listOf(DayOfWeek.SATURDAY), days)
    }

    @Test
    fun `everything greys out while new mail does not notify`() {
        show(MailMuteSettings(rules = listOf(MailMuteRule("a", MailMuteRule.Field.FROM, MailMuteRule.Operator.IS, "x@example.com"))), enabled = false)
        composeRule.onNodeWithText("NTUST Bulletin Board").assertIsNotEnabled()
        composeRule.onNodeWithText("Moodle submission receipts").assertIsNotEnabled()
        composeRule.onNodeWithText("Add rule").assertIsNotEnabled()
        composeRule.onNodeWithText("x@example.com").assertIsNotEnabled()
    }

    @Test
    fun `a preset switches on by itself`() {
        show(MailMuteSettings())
        composeRule.onNodeWithText("NTUST Bulletin Board").assertIsEnabled().performClick()
        assertEquals(listOf(MailMutePreset.BULLETIN to true), presets)
    }

    @Test
    fun `a rule is added with its value trimmed, and not with nothing to match`() {
        show(MailMuteSettings())
        composeRule.onNodeWithText("Add rule").performClick()
        inDialog("Add").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction() and hasText("From")).performTextInput("  digest@example.com ")
        inDialog("Add").assertIsEnabled().performClick()

        assertEquals(1, saved.size)
        assertEquals(MailMuteRule.Field.FROM, saved.single().field)
        assertEquals(MailMuteRule.Operator.CONTAINS, saved.single().op)
        assertEquals("digest@example.com", saved.single().value)
    }

    @Test
    fun `with TalkBack on, a quiet hours time is typed, to the minute`() {
        val accessibility = RuntimeEnvironment.getApplication().getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        var picked: Int? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                QuietTimeDialog(title = "Start", initialMinute = 23 * 60, onDismiss = {}, onConfirm = { picked = it })
            }
        }
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields.assertCountEquals(2)
        fields[1].performTextReplacement("01")
        inDialog("Done").performClick()

        assertEquals(23 * 60 + 1, picked)
    }

    @Test
    fun `an open rule and what was typed into it outlive a recreation`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            TigerDuckAppTheme(darkTheme = false) { LazyColumn { mailMuteItems(MailMuteSettings(), true, actions) } }
        }
        composeRule.onNodeWithText("Add rule").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("From")).performTextInput("digest")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction() and hasText("digest")).assertExists()
        inDialog("Add").assertIsEnabled()
    }

    @Test
    fun `a time reads on the clock the phone is set to, whatever the language's own`() {
        // English (UK) writes 24-hour times by default; the phone set to 12-hour has them so.
        val twelve = quietTimeText(23 * 60, Locale.UK, is24Hour = false)
        assertTrue(twelve, twelve.startsWith("11:00"))
        assertEquals("23:00", quietTimeText(23 * 60, Locale.US, is24Hour = true))
        assertEquals("07:05", quietTimeText(7 * 60 + 5, Locale.UK, is24Hour = true))
        val taiwan = quietTimeText(23 * 60, Locale.TAIWAN, is24Hour = false)
        assertTrue(taiwan, "11:00" in taiwan && "23" !in taiwan)
    }

    @Test
    fun `a rule opens for editing and can be deleted there`() {
        val rule = MailMuteRule("a", MailMuteRule.Field.SUBJECT, MailMuteRule.Operator.CONTAINS, "Weekly")
        show(MailMuteSettings(rules = listOf(rule)))
        composeRule.onNodeWithText("Subject contains").assertExists()
        composeRule.onNodeWithText("Weekly").performClick()
        inDialog("Edit rule").assertExists()

        inDialog("Delete").performClick()

        assertEquals(listOf("a"), deleted)
        assertEquals(emptyList<MailMuteRule>(), saved)
    }
}
