package org.ntust.app.tigerduck.library

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey
import org.ntust.app.tigerduck.util.JvmContext

/**
 * [FlipToLibraryDefaultOffMigration] against what the old first-flip prompt
 * and the Settings switch left behind, when the switch defaulted on.
 */
class FlipToLibraryDefaultOffMigrationTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private val seenKey = FirstTriggerPromptKey.FLIP_TO_LIBRARY.storageKey

    @Before
    fun setUp() {
        context = JvmContext()
        raw().edit().clear().commit()
        prefs = AppPreferences(context)
    }

    private fun raw() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    /** The old prompt's Keep: the prompt answered, the switch left at its default on. */
    private fun keptAtFirstFlip() {
        raw().edit().putBoolean("firstTriggerPromptSeen.$seenKey", true).commit()
    }

    private fun storeSwitch(on: Boolean) {
        raw().edit().putBoolean("flipToLibraryEnabled", on).commit()
    }

    @Test
    fun `the switch is off by default`() {
        assertFalse(prefs.flipToLibraryEnabled)
    }

    @Test
    fun `someone who kept the gesture at the first flip keeps it on`() {
        keptAtFirstFlip()

        assertTrue(FlipToLibraryDefaultOffMigration.runIfNeeded(prefs))
        assertTrue(prefs.flipToLibraryEnabled)
        assertTrue(prefs.hasSeenFirstTriggerPrompt(seenKey))
    }

    @Test
    fun `someone who turned it off at the first flip keeps it off`() {
        keptAtFirstFlip()
        storeSwitch(false)

        assertFalse(FlipToLibraryDefaultOffMigration.runIfNeeded(prefs))
        assertFalse(prefs.flipToLibraryEnabled)
        assertTrue(prefs.hasSeenFirstTriggerPrompt(seenKey))
    }

    @Test
    fun `someone who set the switch in settings keeps their choice and is not asked`() {
        storeSwitch(true)
        assertFalse(FlipToLibraryDefaultOffMigration.runIfNeeded(prefs))
        assertTrue(prefs.flipToLibraryEnabled)
        assertTrue(prefs.hasSeenFirstTriggerPrompt(seenKey))

        raw().edit().clear().commit()
        storeSwitch(false)
        assertFalse(FlipToLibraryDefaultOffMigration.runIfNeeded(prefs))
        assertFalse(prefs.flipToLibraryEnabled)
        assertTrue(prefs.hasSeenFirstTriggerPrompt(seenKey))
    }

    @Test
    fun `someone who never answered is off and will be asked`() {
        assertFalse(FlipToLibraryDefaultOffMigration.runIfNeeded(prefs))
        assertFalse(prefs.flipToLibraryEnabled)
        assertFalse(prefs.hasStoredFlipToLibrary)
        assertFalse(prefs.hasSeenFirstTriggerPrompt(seenKey))
    }

    @Test
    fun `it runs once, so a later maybe-later is not read as the old keep`() {
        FlipToLibraryDefaultOffMigration.runIfNeeded(prefs)
        // Answered with Maybe later afterwards, by a build that did not store the switch.
        keptAtFirstFlip()

        assertFalse(FlipToLibraryDefaultOffMigration.runIfNeeded(prefs))
        assertFalse(prefs.flipToLibraryEnabled)
    }
}
