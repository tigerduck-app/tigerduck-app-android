package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A language picked in an earlier build that the app no longer ships (Arabic, Persian,
 * Hebrew, Urdu) reads as "Follow system" from the preference itself, so every reader
 * (the watch, the backend, the course API, the widgets) gets the reset without a migration.
 */
class AppPreferencesLanguageTest {

    private lateinit var context: Context

    private fun raw() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = JvmContext()
        raw().edit().clear().commit()
    }

    @Test
    fun `a stored right-to-left language reads as follow system`() {
        listOf("ar", "ar-EG", "fa-IR", "he", "iw-IL", "ur-PK").forEach { stored ->
            raw().edit().putString("appLanguage", stored).commit()
            assertEquals(stored, AppLanguageManager.SYSTEM, AppPreferences(context).appLanguage)
        }
    }

    @Test
    fun `the stored value is left as it was`() {
        raw().edit().putString("appLanguage", "ar-EG").commit()
        AppPreferences(context).appLanguage
        assertEquals("ar-EG", raw().getString("appLanguage", null))
    }

    @Test
    fun `a language still shipped reads as it was stored`() {
        raw().edit().putString("appLanguage", "ja").commit()
        assertEquals("ja", AppPreferences(context).appLanguage)
    }
}
