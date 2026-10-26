package org.ntust.app.tigerduck.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DisplayScale
import org.ntust.app.tigerduck.util.JvmContext

/** [DisplayScaleSetting], AppState's copy of the sizes on the Display and text size page. */
class DisplayScaleSettingTest {

    private lateinit var prefs: AppPreferences
    private var widgetRedraws = 0

    @Before
    fun setUp() {
        val context = JvmContext()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
        widgetRedraws = 0
    }

    private fun setting() = DisplayScaleSetting(prefs, onWidgetChange = { widgetRedraws++ })

    @Test
    fun `a pick is stored as its step`() {
        val setting = setting()

        setting[DisplayScale.AppUi] = 0.86f

        assertEquals(0.85f, setting[DisplayScale.AppUi])
        assertEquals(0.85f, prefs.displayScale(DisplayScale.AppUi))
        assertEquals(AppScale(0.85f, 1f), setting.app)
    }

    @Test
    fun `a widget size redraws the widgets, and an app size does not`() {
        val setting = setting()

        setting[DisplayScale.AppText] = 1.2f
        assertEquals(0, widgetRedraws)

        setting[DisplayScale.WidgetUi] = 1.2f
        setting[DisplayScale.WidgetText] = 0.9f
        assertEquals(2, widgetRedraws)

        // The same size again changes nothing.
        setting[DisplayScale.WidgetText] = 0.9f
        assertEquals(2, widgetRedraws)
    }

    @Test
    fun `a reload after a reset reads the defaults`() {
        val setting = setting()
        setting[DisplayScale.AppUi] = 1.3f
        setting[DisplayScale.WidgetText] = 1.5f

        prefs.setDisplayScale(DisplayScale.AppUi, 1f)
        prefs.setDisplayScale(DisplayScale.WidgetText, 1f)
        setting.reload()

        assertEquals(AppScale.DEFAULT, setting.app)
        assertEquals(1f, setting[DisplayScale.WidgetText])
    }

    @Test
    fun `the sizes stored before are the ones it starts with`() {
        prefs.setDisplayScale(DisplayScale.AppText, 1.1f)

        assertEquals(AppScale(1f, 1.1f), setting().app)
    }
}
