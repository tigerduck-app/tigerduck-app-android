package org.ntust.app.tigerduck.wear

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a Wear OS watch has been connected to this phone, which Settings' "Gradient on Wear OS"
 * waits for. False until [check] finds one connected, then true for good, so a watch paired but
 * out of reach for now keeps the row; nothing forgets it but clearing the app's data. In a file of
 * its own, which no reset of the app's settings touches.
 *
 * Only where the build ships the watch app ([WearCompanion.SHIPS]). A look that fails, as where
 * Play services or its Wear OS layer is missing, or that does not answer, is no watch yet: it never
 * reaches Settings, which reads [seen] and asks again each time it opens.
 */
@Singleton
class WatchSeen internal constructor(
    private val prefs: SharedPreferences,
    private val ships: Boolean,
    /** Whether a watch is connected now; may throw, or not answer. */
    private val connected: suspend () -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefsOf(context),
        WearCompanion.SHIPS,
        { WearCompanion.hasConnectedWatch(context) },
    )

    private val state = MutableStateFlow(ships && prefs.getBoolean(KEY_SEEN, false))

    val seen: StateFlow<Boolean> = state.asStateFlow()

    /** Looks for a connected watch, unless one has been seen already, and remembers one it finds. */
    suspend fun check() {
        if (!ships || state.value) return
        val found = try {
            withTimeoutOrNull(LOOK_TIMEOUT_MS) { connected() } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!found) return
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
        state.value = true
    }

    internal companion object {
        private const val KEY_SEEN = "watch_seen"
        private const val LOOK_TIMEOUT_MS = 10_000L

        fun prefsOf(context: Context): SharedPreferences = context.getSharedPreferences("wear_watch_seen", Context.MODE_PRIVATE)
    }
}
