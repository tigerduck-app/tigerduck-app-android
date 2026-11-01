package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app has no right-to-left locale, so its screens stay left to right on every device.
 * Compose reads the layout direction from the configuration, so a phone set to Arabic or
 * Hebrew would otherwise mirror every page around its English strings.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "ar-rEG-ldrtl")
class TigerDuckAppThemeDirectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a page under the theme is left to right on a right-to-left configuration`() {
        var seen: LayoutDirection? = null
        composeRule.setContent {
            TigerDuckAppTheme { seen = LocalLayoutDirection.current }
        }
        composeRule.waitForIdle()
        assertEquals(LayoutDirection.Ltr, seen)
    }

    @Test
    fun `a page outside the theme does read the right-to-left configuration`() {
        // Guards the test above: the configuration really is right to left here.
        var seen: LayoutDirection? = null
        composeRule.setContent { seen = LocalLayoutDirection.current }
        composeRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, seen)
    }
}
