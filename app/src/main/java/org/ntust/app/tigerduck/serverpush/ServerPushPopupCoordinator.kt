package org.ntust.app.tigerduck.serverpush

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

private val Context.serverPushDataStore by preferencesDataStore("server_push")
// JSON-encoded ordered List<String> — DataStore's stringSet round-trip loses
// insertion order, so a "FIFO trim at 100" against a Set evicts arbitrary
// entries instead of the oldest.
private val SEEN_KEY = stringPreferencesKey("seen_ids_json")
// Legacy unordered storage from the first cut; consumed once on next request
// after upgrade so an existing user's already-dismissed ids survive.
private val LEGACY_SEEN_KEY = stringSetPreferencesKey("seen_ids")

data class ServerPopupRequest(
    val notificationId: String,
    val title: String,
    val body: String,
)

/**
 * Routes "open the app to show a popup" deep links into a queue the top-most
 * Compose screen observes one entry at a time. Two popups arriving back-to-back
 * are both shown (the second is queued behind the first) rather than the second
 * silently replacing the first. Dedupes replays via a DataStore-backed ordered
 * FIFO list capped at 100 entries — written only when the user acknowledges,
 * so a crash between request() and the AlertDialog actually rendering can't
 * permanently silence the popup.
 */
@Singleton
class ServerPushPopupCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val gson = Gson()
    private val lock = Any()
    // Behind-the-current-popup queue. Items move into _pending one at a time
    // as the user acknowledges, so a burst of popups is never silently lost.
    private val queue = ArrayDeque<ServerPopupRequest>()
    // Same-process dedupe: any id currently surfaced in _pending or waiting
    // in queue. Distinct from the on-disk "seen" set, which only records ids
    // the user has actually acknowledged — see request() / acknowledge().
    private val activeIds = HashSet<String>()

    private val _pending = MutableStateFlow<ServerPopupRequest?>(null)
    val pending: StateFlow<ServerPopupRequest?> = _pending.asStateFlow()

    suspend fun request(req: ServerPopupRequest) {
        // Quick same-process check first — if the popup is already on
        // screen or queued, the user will see it; no need to touch disk.
        synchronized(lock) {
            if (req.notificationId in activeIds) return
        }
        // Cross-process dedupe: did the user already acknowledge this id in
        // a prior session? Reading without `edit { }` is intentional — we
        // must not mark seen yet (a kill before AlertDialog renders would
        // otherwise lose the popup forever).
        val prefs = context.serverPushDataStore.data.first()
        if (req.notificationId in readSeen(prefs)) return
        synchronized(lock) {
            // Re-check under the lock — a concurrent request() for the same
            // id could have raced past the suspending read above.
            if (!activeIds.add(req.notificationId)) return
            if (_pending.value == null) {
                _pending.value = req
            } else {
                queue.addLast(req)
            }
        }
    }

    fun acknowledge() {
        val ack: ServerPopupRequest?
        synchronized(lock) {
            ack = _pending.value
            _pending.value = queue.removeFirstOrNull()
            ack?.let { activeIds.remove(it.notificationId) }
        }
        // Persist *after* the user actually saw and dismissed the popup. If
        // we wrote at request() time, a crash or process death before the
        // AlertDialog mounted would mark the id seen and permanently
        // suppress future taps of the same notification.
        ack?.let { persistSeen(it.notificationId) }
    }

    private fun persistSeen(notificationId: String) {
        appScope.launch {
            context.serverPushDataStore.edit { prefs ->
                val current = readSeen(prefs)
                if (notificationId in current) return@edit
                val capped = (current + notificationId).takeLast(MAX_SEEN)
                prefs[SEEN_KEY] = gson.toJson(capped)
                // Drop the legacy key after the first successful migration so
                // it doesn't keep growing alongside the new key.
                prefs.remove(LEGACY_SEEN_KEY)
            }
        }
    }

    private fun readSeen(prefs: Preferences): List<String> {
        prefs[SEEN_KEY]?.let { json ->
            return runCatching {
                gson.fromJson(json, Array<String>::class.java).toList()
            }.getOrElse { emptyList() }
        }
        // First request after upgrade — preserve membership from the legacy
        // unordered Set. The original FIFO insertion order is unrecoverable
        // (Set has none), so we accept that those old entries trim in a
        // platform-defined order; the new entries appended on top trim FIFO.
        return prefs[LEGACY_SEEN_KEY].orEmpty().toList()
    }

    private companion object {
        const val MAX_SEEN = 100
    }
}
