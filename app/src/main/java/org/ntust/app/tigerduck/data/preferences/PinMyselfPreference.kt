package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Pin myself": the account's own row first in its section of a course's members list. On by
 * default, as on the iPhone (tigerduck-app#1032). Device-local and kept at a sign-out, in
 * [AppPreferences]' file, whose full reset turns it back on ([reload]).
 */
class PinMyselfPreference internal constructor(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())

    /** For the members list, whose switch in its ⋮ and in Settings sit apart from it. */
    val flow: StateFlow<Boolean> = state.asStateFlow()

    var value: Boolean
        get() = state.value
        set(on) {
            prefs.edit().putBoolean(PIN_MYSELF_KEY, on).apply()
            state.value = on
        }

    /** Reads the file again, once [AppPreferences.clearAllPrefs] has emptied it. */
    internal fun reload() {
        state.value = read()
    }

    private fun read() = prefs.getBoolean(PIN_MYSELF_KEY, true)

    private companion object {
        const val PIN_MYSELF_KEY = "pinMyselfInCourseMembers"
    }
}
