package org.ntust.app.tigerduck.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptKey

/**
 * The flip-to-library switch: stored in [prefs], and read by Compose, which
 * follows a change. Apart from [org.ntust.app.tigerduck.ui.AppState] so a test
 * can run it.
 */
class FlipToLibrarySetting(private val prefs: AppPreferences) {

    private var state by mutableStateOf(prefs.flipToLibraryEnabled)

    /**
     * Written even when it reads the same, the first time: a stored off is an
     * answer, such as the prompt's Maybe later, which the default off is not
     * (see [FlipToLibraryDefaultOffMigration]).
     */
    var enabled: Boolean
        get() = state
        set(value) {
            if (state == value && prefs.hasStoredFlipToLibrary) return
            state = value
            prefs.flipToLibraryEnabled = value
        }

    /**
     * The switch in Settings. A change there answers the question a flip asks
     * with the switch off, which then is not asked.
     */
    fun setFromSettings(value: Boolean) {
        prefs.setFirstTriggerPromptSeen(FirstTriggerPromptKey.FLIP_TO_LIBRARY.storageKey, true)
        enabled = value
    }

    /** Reads the stored switch again, after a reset wiped it. */
    fun reload() {
        state = prefs.flipToLibraryEnabled
    }
}
