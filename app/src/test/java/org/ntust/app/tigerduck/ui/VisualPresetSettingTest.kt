package org.ntust.app.tigerduck.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers [VisualPresetSetting], AppState's copy of the interface style, from
 * the Settings pick to the style the root theme hands every screen.
 * [AppState] itself needs a real Hilt graph, so its copy lives in this class.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class VisualPresetSettingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    @Test
    fun `a pick reaches every screen through the root theme at once, and is stored`() {
        val setting = VisualPresetSetting(prefs)
        var seen: VisualPreset? = null
        composeRule.setContent {
            // As MainActivity does: the root theme takes AppState's copy.
            TigerDuckAppTheme(darkTheme = false, visualPreset = setting.value) {
                seen = LocalVisualStylePolicy.current.preset
            }
        }
        composeRule.waitForIdle()
        assertEquals(VisualPreset.TigerDuck, seen)

        composeRule.runOnIdle { setting.value = VisualPreset.Tat }
        composeRule.waitForIdle()

        assertEquals(VisualPreset.Tat, seen)
        assertEquals(VisualPreset.Tat, prefs.visualPreset)
        assertEquals(VisualPreset.Tat, VisualPresetSetting(prefs).value)
    }

    @Test
    fun `only a pick that changes the style is announced, so the widgets redraw once`() {
        var changes = 0
        val setting = VisualPresetSetting(prefs, onChange = { changes++ })

        setting.value = VisualPreset.TigerDuck
        assertEquals(0, changes)

        setting.value = VisualPreset.Tat
        assertEquals(1, changes)

        setting.value = VisualPreset.Tat
        assertEquals(1, changes)

        // Stored before it is announced: the widgets read the preference when they redraw.
        var storedAtChange: VisualPreset? = null
        val reading = VisualPresetSetting(prefs, onChange = { storedAtChange = prefs.visualPreset })
        reading.value = VisualPreset.TigerDuck
        assertEquals(VisualPreset.TigerDuck, storedAtChange)
    }

    @Test
    fun `after a full reset the copy reads TigerDuck again`() {
        val setting = VisualPresetSetting(prefs)
        setting.value = VisualPreset.Tat
        prefs.clearAllPrefs()
        setting.reload()
        assertEquals(VisualPreset.TigerDuck, setting.value)
    }
}
