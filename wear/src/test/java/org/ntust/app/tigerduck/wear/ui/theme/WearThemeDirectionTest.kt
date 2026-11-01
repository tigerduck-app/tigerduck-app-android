package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch app has no right-to-left locale either, so its screens stay left to right on
 * every watch, whatever language the watch is set to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar-rEG-ldrtl")
class WearThemeDirectionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a page under the theme is left to right on a right-to-left configuration`() {
        var seen: LayoutDirection? = null
        rule.setContent {
            WearTheme(accent = Color.Red, accentGradient = null, paddingDp = 12) {
                seen = LocalLayoutDirection.current
            }
        }
        rule.waitForIdle()
        assertEquals(LayoutDirection.Ltr, seen)
    }

    @Test
    fun `a page outside the theme does read the right-to-left configuration`() {
        // Guards the test above: the configuration really is right to left here.
        var seen: LayoutDirection? = null
        rule.setContent { seen = LocalLayoutDirection.current }
        rule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, seen)
    }
}
