package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.appicon.AppIcon

/**
 * The app icon picked in Settings, by the iPhone's name for it (tigerduck-app#1026): nothing for
 * [AppIcon.DEFAULT], so a device that never picked one follows the build's own icon. Per device,
 * never synced, and kept at a sign-out, in [AppPreferences]' file, whose full reset forgets it
 * ([reload]). [org.ntust.app.tigerduck.appicon.LauncherIcons] shows it.
 */
class AppIconPreference internal constructor(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())

    val flow: StateFlow<AppIcon> = state.asStateFlow()

    var value: AppIcon
        get() = state.value
        set(icon) {
            val stored = icon.stored
            if (stored == null) prefs.edit().remove(APP_ICON_KEY).apply() else prefs.edit().putString(APP_ICON_KEY, stored).apply()
            state.value = icon
        }

    /** Reads the file again, once [AppPreferences.clearAllPrefs] has emptied it. */
    internal fun reload() {
        state.value = read()
    }

    private fun read() = AppIcon.of(prefs.getString(APP_ICON_KEY, null))

    private companion object {
        const val APP_ICON_KEY = "appIcon"
    }
}
