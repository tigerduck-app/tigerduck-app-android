package org.ntust.app.tigerduck.serverpush

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.serverPushDataStore by preferencesDataStore("server_push")
private val SEEN_KEY = stringSetPreferencesKey("seen_ids")

data class ServerPopupRequest(
    val notificationId: String,
    val title: String,
    val body: String,
)

/**
 * Routes "open the app to show a popup" deep links into a StateFlow the
 * top-most Compose screen observes. Dedupes replays via a DataStore-backed
 * FIFO set capped at 100 entries.
 */
@Singleton
class ServerPushPopupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _pending = MutableStateFlow<ServerPopupRequest?>(null)
    val pending: StateFlow<ServerPopupRequest?> = _pending.asStateFlow()

    suspend fun request(req: ServerPopupRequest) {
        val seen = context.serverPushDataStore.data.first()[SEEN_KEY].orEmpty()
        if (req.notificationId in seen) return
        context.serverPushDataStore.edit { prefs ->
            val current = prefs[SEEN_KEY].orEmpty().toMutableList()
            current.add(req.notificationId)
            if (current.size > 100) {
                current.subList(0, current.size - 100).clear()
            }
            prefs[SEEN_KEY] = current.toSet()
        }
        _pending.value = req
    }

    fun acknowledge() {
        _pending.value = null
    }
}
