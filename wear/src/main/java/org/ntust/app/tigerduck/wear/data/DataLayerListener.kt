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
            if (item.uri.path != WearProtocol.Schedule.PATH) continue
            val map = DataMapItem.fromDataItem(item).dataMap
            val courses = map.getByteArray(WearProtocol.Schedule.KEY_COURSES) ?: continue
            val accent = map.getString(WearProtocol.Schedule.KEY_ACCENT) ?: SchedulePersistence.DEFAULT_ACCENT
            val syncedAt = map.getLong(WearProtocol.Schedule.KEY_SYNCED_AT)
            val loggedIn = map.getBoolean(WearProtocol.Schedule.KEY_LOGGED_IN)
            val language = map.getString(WearProtocol.Schedule.KEY_LANGUAGE)
            ScheduleRepository.get(this).write(courses, accent, syncedAt, loggedIn, language)
            Log.d(TAG, "received snapshot, lag=${System.currentTimeMillis() - syncedAt} ms")
            notifyTileAndComplication(applicationContext)
        }
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
