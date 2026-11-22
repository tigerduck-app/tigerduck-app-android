package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/**
 * School Mail's text size, as the iPhone has it (tigerduck-app#1047): 100% by default, one for every
 * mail, kept on this device under the iPhone's key, within the steps' ends whatever is stored.
 */
class MailTextSizePreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = JvmContext()
        raw().edit().clear().commit()
    }

    private fun raw() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    @Test
    fun `a mail is at 100 percent until a size is picked`() {
        val prefs = AppPreferences(context)
        assertEquals(100, prefs.mailTextSize.value)
        assertEquals(100, prefs.mailTextSize.flow.value)
    }

    @Test
    fun `the size reaches the flow, is kept under its own key and survives a restart`() {
        val prefs = AppPreferences(context)
        prefs.mailTextSize.value = 130

        assertEquals(130, prefs.mailTextSize.flow.value)
        assertEquals(130, raw().all["mailTextZoom"])
        assertEquals(130, AppPreferences(context).mailTextSize.value)
    }

    @Test
    fun `a size past the steps' ends is held to them, stored or set`() {
        raw().edit().putInt("mailTextZoom", 900).commit()
        assertEquals(200, AppPreferences(context).mailTextSize.value)

        raw().edit().putString("mailTextZoom", "large").commit()
        assertEquals(100, AppPreferences(context).mailTextSize.value)

        val prefs = AppPreferences(context)
        prefs.mailTextSize.value = 10
        assertEquals(50, prefs.mailTextSize.value)
        assertEquals(50, raw().all["mailTextZoom"])
    }

    @Test
    fun `a full reset brings it back to 100 percent`() {
        val prefs = AppPreferences(context)
        prefs.mailTextSize.value = 150

        prefs.clearAllPrefs()

        assertEquals(100, prefs.mailTextSize.flow.value)
    }
}
