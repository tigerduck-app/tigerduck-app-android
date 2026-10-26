package org.ntust.app.tigerduck.widget

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.AccentSetting
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.courseColorPalette
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.widget.content.weekCourseFill
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers [resolveWidgetAccentColor]: the widgets draw the fixed accent, the
 * swatch last picked, also while the app follows the wallpaper.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class WidgetAccentColorTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    @Test
    fun `with the wallpaper picked the widgets keep the swatch last picked`() {
        AccentSetting(prefs).apply {
            pick(0xFF2D55)
            pickWallpaper()
        }

        assertEquals(Color(0xFFFF2D55), resolveWidgetAccentColor(prefs, isDark = false))
        assertEquals(Color(0xFFFF375F), resolveWidgetAccentColor(prefs, isDark = true))
    }

    @Test
    fun `under TAT with the wallpaper picked the Week widget's class in session takes the swatch`() {
        AccentSetting(prefs).apply {
            pick(0xFF2D55)
            pickWallpaper()
        }
        prefs.visualPreset = VisualPreset.Tat
        val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
        val courseColors = mapOf(calculus.courseNo to courseColorPalette[2])
        val style = VisualStylePolicy(prefs.visualPreset)

        // As BaseClassTableWidget builds them, in either theme.
        listOf(false, true).forEach { isDark ->
            val colors = (if (isDark) WidgetTheme.Dark else WidgetTheme.Light)
                .copy(highlight = resolveWidgetAccentColor(prefs, isDark))
            val swatch = if (isDark) Color(0xFFFF375F) else Color(0xFFFF2D55)
            assertEquals(swatch, weekCourseFill(calculus, true, colors, style, courseColors))
            assertEquals(courseColorPalette[2].pastel(), weekCourseFill(calculus, false, colors, style, courseColors))
        }
    }

    @Test
    fun `a stored -1 draws Blue, not white`() {
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit()
            .putInt("accentColorHex", -1).commit()

        assertEquals(Color(0xFF007AFF), resolveWidgetAccentColor(prefs, isDark = false))
        assertEquals(Color(0xFF0A84FF), resolveWidgetAccentColor(prefs, isDark = true))
    }

    @Test
    fun `text on a preset accent stays white`() {
        AccentSetting(prefs).pick(0xFF2D55)
        assertEquals(Color.White, resolveWidgetOnAccentColor(prefs, isDark = false))
        assertEquals(Color.White, resolveWidgetOnAccentColor(prefs, isDark = true))
    }

    @Test
    fun `text on a light accent of the user's own turns dark`() {
        AccentSetting(prefs).pick(0xFFFFFF)
        assertEquals(Color(0xFF1C1C1E), resolveWidgetOnAccentColor(prefs, isDark = false))
    }
}
