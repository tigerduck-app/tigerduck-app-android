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
            if (item.uri.path != SCHEDULE_PATH) continue
            val map = DataMapItem.fromDataItem(item).dataMap
            val courses = map.getByteArray(KEY_COURSES) ?: continue
            val accent = map.getString(KEY_ACCENT) ?: SchedulePersistence.DEFAULT_ACCENT
            val syncedAt = map.getLong(KEY_SYNCED_AT)
            val loggedIn = map.getBoolean(KEY_LOGGED_IN)
            ScheduleRepository.get(this).write(courses, accent, syncedAt, loggedIn)
            Log.d(TAG, "received snapshot, lag=${System.currentTimeMillis() - syncedAt} ms")
            notifyTileAndComplication(applicationContext)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SCHEDULE_PATH = "/tigerduck/schedule"
        const val KEY_COURSES = "courses"
        const val KEY_ACCENT = "accentHex"
        const val KEY_SYNCED_AT = "syncedAtMs"
        const val KEY_LOGGED_IN = "loggedIn"
        private const val TAG = "WearBridge"
    }
}

/**
 * Stub for now. Tasks 11 (Tile) and 12 (Complication) will fill this in.
 * Keeping it as a separate function so the listener doesn't need to know
 * about either surface yet.
 */
internal fun notifyTileAndComplication(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
    // Intentionally empty. Restored in Task 12 once the services exist.
}
