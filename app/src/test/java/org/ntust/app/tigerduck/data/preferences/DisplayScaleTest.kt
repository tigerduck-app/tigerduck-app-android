package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/** The sizes on the Display and text size page, as stored and read back. */
class DisplayScaleTest {

    private val context: Context = JvmContext()
    private val file get() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        file.edit().clear().commit()
    }

    @Test
    fun `a size between two steps is the nearer one`() {
        assertEquals(1.1f, DisplayScale.AppUi.normalize(1.12f))
        assertEquals(0.75f, DisplayScale.AppUi.normalize(0.76f))
        assertEquals(1.5f, DisplayScale.AppText.normalize(1.45f))
    }

    @Test
    fun `a size outside the steps, or no number, is the phone's own`() {
        assertEquals(1f, DisplayScale.AppUi.normalize(0.5f))
        assertEquals(1f, DisplayScale.AppUi.normalize(1.5f))
        assertEquals(1f, DisplayScale.WidgetText.normalize(Float.NaN))
        assertEquals(1f, DisplayScale.WidgetText.normalize(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `nothing stored reads as the phone's own size`() {
        val prefs = AppPreferences(context)

        DisplayScale.entries.forEach { assertEquals(it.name, 1f, prefs.displayScale(it)) }
    }

    @Test
    fun `a size is stored as its step and read back`() {
        val prefs = AppPreferences(context)

        prefs.setDisplayScale(DisplayScale.AppUi, 0.86f)
        prefs.setDisplayScale(DisplayScale.WidgetText, 1.3f)

        assertEquals(0.85f, prefs.displayScale(DisplayScale.AppUi))
        assertEquals(1.3f, prefs.displayScale(DisplayScale.WidgetText))
        assertEquals(1f, prefs.displayScale(DisplayScale.AppText))
        assertEquals(0.85f, AppPreferences.storedDisplayScale(context, DisplayScale.AppUi))
    }

    @Test
    fun `a stored value that is no float or out of range reads as the phone's own`() {
        file.edit()
            .putString("appUiScale", "big")
            .putFloat("appTextScale", 9f)
            .commit()
        val prefs = AppPreferences(context)

        assertEquals(1f, prefs.displayScale(DisplayScale.AppUi))
        assertEquals(1f, prefs.displayScale(DisplayScale.AppText))
    }

    @Test
    fun `each step has its position on the slider`() {
        assertEquals(0, DisplayScale.AppUi.stepIndex(0.75f))
        assertEquals(5, DisplayScale.AppUi.stepIndex(1f))
        assertEquals(4, DisplayScale.AppText.stepIndex(1f))
        assertEquals(DisplayScale.AppText.steps.lastIndex, DisplayScale.AppText.stepIndex(1.5f))
    }
}
