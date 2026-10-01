package org.ntust.app.tigerduck.data.preferences

import android.os.Build
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires when the language the app's screens render in has changed, for what
 * keeps the language it was last given until told: the notification channel
 * names, the Live Update, the server's push copy, the calendar's built rows.
 *
 * Two sources, because neither covers every case:
 * - [onLocalesChanged], from `Application.onConfigurationChanged`: a change of
 *   phone language under "Follow system", which never touches the stored
 *   setting, and on API 33+ an in-app switch once it has reached the process.
 * - [AppPreferences.appLanguageChanged], below API 33 only. There AppCompat
 *   applies an in-app language to Activities alone, so the Application never
 *   sees a configuration change for it.
 *
 * The setting is left out on API 33+ on purpose. It changes before the new
 * language reaches the application context, so a listener reading "Follow
 * system" from that context at once gets the language the user just left —
 * and the configuration change follows anyway.
 */
@Singleton
class UiLanguageMonitor @Inject constructor(prefs: AppPreferences) {
    private val localesChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: Flow<Unit> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localesChanged.asSharedFlow()
        } else {
            merge(localesChanged, prefs.appLanguageChanged)
        }

    /** The application's locales changed. Called by `TigerDuckApp` alone. */
    fun onLocalesChanged() {
        localesChanged.tryEmit(Unit)
    }
}
