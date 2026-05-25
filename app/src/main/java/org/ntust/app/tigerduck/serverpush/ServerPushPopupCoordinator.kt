package org.ntust.app.tigerduck.serverpush

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * FIFO list capped at 100 entries.
 */
@Singleton
class ServerPushPopupCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    private val lock = Any()
    // Behind-the-current-popup queue. Items move into _pending one at a time
    // as the user acknowledges, so a burst of popups is never silently lost.
    private val queue = ArrayDeque<ServerPopupRequest>()

    private val _pending = MutableStateFlow<ServerPopupRequest?>(null)
    val pending: StateFlow<ServerPopupRequest?> = _pending.asStateFlow()

    suspend fun request(req: ServerPopupRequest) {
        var wasNew = false
        // DataStore's edit block serializes concurrent calls, so the dedupe
        // read-modify-write is atomic — two simultaneous request() coroutines
        // with the same nid cannot both pass the membership check.
        context.serverPushDataStore.edit { prefs ->
            val current = readSeen(prefs)
            if (req.notificationId in current) return@edit
            wasNew = true
            val capped = (current + req.notificationId).takeLast(MAX_SEEN)
            prefs[SEEN_KEY] = gson.toJson(capped)
            // Drop the legacy key after the first successful migration so it
            // doesn't keep growing alongside the new key.
            prefs.remove(LEGACY_SEEN_KEY)
        }
        if (!wasNew) return
        synchronized(lock) {
            if (_pending.value == null) {
                _pending.value = req
            } else {
                queue.addLast(req)
            }
        }
    }

    fun acknowledge() {
        synchronized(lock) {
            _pending.value = queue.removeFirstOrNull()
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
