package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccentColorSwatchesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Config(sdk = [30])
    @Test
    fun `wallpaper swatch is hidden on Android 11 and earlier`() {
        composeRule.setContent {
            AccentColorSwatches(
                selectedHex = 0x007AFF,
                isDark = false,
                onSelect = {},
            )
        }
        composeRule.onNodeWithContentDescription("Wallpaper").assertDoesNotExist()
    }

    @Config(sdk = [31])
    @Test
    fun `wallpaper swatch is visible and selectable on Android 12 and later`() {
        var selected: Int? = null
        composeRule.setContent {
            AccentColorSwatches(
                selectedHex = AppPreferences.ACCENT_WALLPAPER,
                isDark = false,
                onSelect = { selected = it },
            )
        }
        val wallpaperNode = composeRule.onNodeWithContentDescription("Wallpaper")
        wallpaperNode.assertExists()
        wallpaperNode.assertIsSelected()
        wallpaperNode.performClick()
        assertEquals(AppPreferences.ACCENT_WALLPAPER, selected)
    }
}
