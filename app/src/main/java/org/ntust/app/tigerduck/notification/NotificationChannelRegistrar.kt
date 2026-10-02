package org.ntust.app.tigerduck.notification

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates [NotificationChannels] in the current language, and makes sure they
 * exist before anything posts to one.
 *
 * A post to a channel that does not exist is dropped without a word, and the
 * channels are otherwise created only at launch and on a change of language.
 * If the launch one fails, every poster's [ensureRegistered] is the retry, so
 * a one-shot notification such as the class reminder is not lost to it.
 */
@Singleton
class NotificationChannelRegistrar @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
) {
    @Volatile
    private var registered = false

    /**
     * Create or rename every channel, and on the first launch to get here drop
     * the old Live Update channels.
     *
     * Never throws. A NotificationManager call fails while system_server
     * restarts; from `Application.onCreate` that would crash the launch,
     * background wakes included, and from a collector it would end it for
     * good. Caught, the cost is names in the old language until the next
     * launch or switch, and missing channels until the next post retries.
     */
    fun register() {
        runCatching {
            NotificationChannels.registerAll(context, prefs.appLanguage)
            registered = true
            if (!prefs.legacyNotificationChannelsDeleted) {
                NotificationChannels.deleteLegacyChannels(context)
                prefs.legacyNotificationChannelsDeleted = true
            }
        }.onFailure { Log.w(TAG, "Could not register notification channels", it) }
    }

    /**
     * Call just before posting. One volatile read once [register] has
     * succeeded in this process; until then, another attempt at it.
     */
    fun ensureRegistered() {
        if (!registered) register()
    }

    private companion object {
        const val TAG = "NotificationChannels"
    }
}
