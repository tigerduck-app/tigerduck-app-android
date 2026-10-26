package org.ntust.app.tigerduck.library

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey

/**
 * One-shot for the update where flip-to-library turns off by default, to be
 * offered rather than on from the start. A user who kept the gesture at the
 * old first-flip prompt stored no value, as it defaulted on, and would lose
 * it with the new default, so it is stored on. Anyone who
 * chose either way, at that prompt or in Settings, is not asked again.
 * Mirrors the Apple app's `FlipToLibraryDefaultOffMigration`.
 */
internal object FlipToLibraryDefaultOffMigration {

    /** Returns whether it stored the switch. */
    fun runIfNeeded(prefs: AppPreferences): Boolean {
        if (prefs.flipToLibraryDefaultOffChecked) return false
        prefs.flipToLibraryDefaultOffChecked = true
        val seen = FirstTriggerPromptKey.FLIP_TO_LIBRARY.storageKey
        if (prefs.hasStoredFlipToLibrary) {
            prefs.setFirstTriggerPromptSeen(seen, true)
            return false
        }
        if (!prefs.hasSeenFirstTriggerPrompt(seen)) return false
        prefs.flipToLibraryEnabled = true
        return true
    }
}
