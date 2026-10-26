package org.ntust.app.tigerduck.library

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey
import org.ntust.app.tigerduck.ui.navigation.FlipToLibraryPrompt
import org.ntust.app.tigerduck.util.JvmContext

/** The prompt's answers and the Settings switch, as stored. */
class FlipToLibrarySettingTest {

    private lateinit var prefs: AppPreferences
    private lateinit var setting: FlipToLibrarySetting
    private lateinit var controller: FirstTriggerPromptController
    private val key = FirstTriggerPromptKey.FLIP_TO_LIBRARY

    @Before
    fun setUp() {
        val context = JvmContext()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
        setting = FlipToLibrarySetting(prefs)
        controller = FirstTriggerPromptController(prefs)
    }

    @Test
    fun `maybe later stores the switch off, an answer the default off is not`() {
        FlipToLibraryPrompt.request(controller, setting)
        controller.finish(accept = false)

        assertTrue(prefs.hasStoredFlipToLibrary)
        assertFalse(prefs.flipToLibraryEnabled)
        assertTrue(controller.hasSeen(key))
    }

    @Test
    fun `turn on stores the switch on`() {
        FlipToLibraryPrompt.request(controller, setting)
        controller.finish(accept = true)

        assertTrue(prefs.flipToLibraryEnabled)
        assertTrue(setting.enabled)
    }

    @Test
    fun `turn on opens Library for the flip that asked, with the switch already on`() {
        var switchWhenOpened: Boolean? = null
        FlipToLibraryPrompt.request(controller, setting, onTurnedOn = { switchWhenOpened = setting.enabled })
        controller.finish(accept = true)

        assertEquals(true, switchWhenOpened)
    }

    @Test
    fun `maybe later opens nothing`() {
        var opened = false
        FlipToLibraryPrompt.request(controller, setting, onTurnedOn = { opened = true })
        controller.finish(accept = false)

        assertFalse(opened)
    }

    @Test
    fun `a change to the switch in settings answers the question`() {
        setting.setFromSettings(true)

        assertTrue(controller.hasSeen(key))
        assertTrue(prefs.flipToLibraryEnabled)
        FlipToLibraryPrompt.request(controller, setting)
        assertTrue(controller.pending.value == null)
    }
}
