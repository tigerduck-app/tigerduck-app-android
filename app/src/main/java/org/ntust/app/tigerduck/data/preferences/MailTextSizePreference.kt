package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.util.PageZoom

/**
 * School Mail's text size, in percent: one for every mail, picked in a message's ⋮, as on the
 * iPhone (tigerduck-app#1047), within [PageZoom]'s steps. Device-local and kept at a sign-out, in
 * [AppPreferences]' file, whose full reset brings it back to 100% ([reload]).
 */
class MailTextSizePreference internal constructor(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())

    /** For the message screen, whose menu sets it and whose body follows it. */
    val flow: StateFlow<Int> = state.asStateFlow()

    var value: Int
        get() = state.value
        set(percent) {
            val clamped = PageZoom.clamp(percent)
            prefs.edit().putInt(MAIL_TEXT_ZOOM_KEY, clamped).apply()
            state.value = clamped
        }

    /** Reads the file again, once [AppPreferences.clearAllPrefs] has emptied it. */
    internal fun reload() {
        state.value = read()
    }

    /** What is stored, held to the steps' ends; 100% for nothing, or for what is no number. */
    private fun read() = PageZoom.clamp(runCatching { prefs.getInt(MAIL_TEXT_ZOOM_KEY, PageZoom.DEFAULT) }.getOrDefault(PageZoom.DEFAULT))

    private companion object {
        /** The iPhone's name for it. */
        const val MAIL_TEXT_ZOOM_KEY = "mailTextZoom"
    }
}
