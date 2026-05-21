package org.ntust.app.tigerduck.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.WearProtocol

class DataLayerListener : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Copy events out before the buffer is closed by the platform.
        val events = dataEvents.map { it.freeze() }
        scope.launch { handleEvents(events) }
    }

    private suspend fun handleEvents(events: List<DataEvent>) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            when (item.uri.path) {
                WearProtocol.Schedule.PATH -> handleSchedule(item)
                WearProtocol.LibraryCredentials.PATH -> handleLibraryCredentials(item)
            }
        }
    }

    private suspend fun handleSchedule(item: com.google.android.gms.wearable.DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        val courses = map.getByteArray(WearProtocol.Schedule.KEY_COURSES) ?: return
        val accent = map.getString(WearProtocol.Schedule.KEY_ACCENT) ?: SchedulePersistence.DEFAULT_ACCENT
        val syncedAt = map.getLong(WearProtocol.Schedule.KEY_SYNCED_AT)
        val loggedIn = map.getBoolean(WearProtocol.Schedule.KEY_LOGGED_IN)
        val language = map.getString(WearProtocol.Schedule.KEY_LANGUAGE)
        try {
            ScheduleRepository.get(this@DataLayerListener)
                .write(courses, accent, syncedAt, loggedIn, language)
        } catch (e: Exception) {
            // Most likely a malformed/truncated gzip payload from the Data Layer
            // (decompress() throws ZipException). Skip this packet rather than
            // letting the exception escape the SupervisorJob's launch and crash
            // the service via the thread's uncaught-exception handler.
            Log.e(TAG, "failed to persist snapshot", e)
            return
        }
        Log.d(TAG, "received snapshot, lag=${System.currentTimeMillis() - syncedAt} ms")
        notifyTileAndComplication(applicationContext)
    }

    private fun handleLibraryCredentials(item: com.google.android.gms.wearable.DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        val hasCredentials = map.getBoolean(WearProtocol.LibraryCredentials.KEY_HAS_CREDENTIALS)
        val username = map.getString(WearProtocol.LibraryCredentials.KEY_USERNAME)
        val password = map.getString(WearProtocol.LibraryCredentials.KEY_PASSWORD)
        val token = map.getString(WearProtocol.LibraryCredentials.KEY_TOKEN)
        val tokenExpiry = map.getLong(WearProtocol.LibraryCredentials.KEY_TOKEN_EXPIRY)
        val version = map.getLong(WearProtocol.LibraryCredentials.KEY_VERSION)
        // Stamp on receive when the phone is older than this watch build and
        // doesn't publish `issuedAtMs` yet — gives the new TTL a starting
        // anchor instead of treating creds as orphan-on-arrival.
        val issuedAtMs = map.getLong(WearProtocol.LibraryCredentials.KEY_ISSUED_AT_MS)
            .takeIf { it > 0L } ?: System.currentTimeMillis()

        val store = WatchLibraryCredentialStore.get(this)
        val result = store.applyPush(
            hasCredentials = hasCredentials,
            username = username,
            password = password,
            token = token,
            tokenExpiry = tokenExpiry,
            version = version,
            issuedAtMs = issuedAtMs,
        )
        Log.d(TAG, "library credentials push: $result (version=$version, has=$hasCredentials)")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WearBridge"
    }
}

internal fun notifyTileAndComplication(context: android.content.Context) {
    org.ntust.app.tigerduck.wear.tile.NextClassTileService.requestUpdate(context)
    org.ntust.app.tigerduck.wear.complication.NextClassComplicationService.requestUpdate(context)
}
