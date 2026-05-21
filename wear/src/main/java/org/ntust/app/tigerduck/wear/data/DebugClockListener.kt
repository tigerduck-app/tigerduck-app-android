package org.ntust.app.tigerduck.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.wear.BuildConfig
import org.ntust.app.tigerduck.wear.complication.NextClassComplicationService
import org.ntust.app.tigerduck.wear.tile.NextClassTileService

/**
 * Receives debug-clock-override updates from the phone via the Wearable Data
 * Layer. Persists to wear's debug_clock prefs and updates AppClock so wear
 * screens, complications, and tile read the fake time on next refresh.
 */
class DebugClockListener : WearableListenerService() {

    private val store by lazy { WearDebugClockPrefsStore(applicationContext) }

    override fun onDataChanged(events: DataEventBuffer) {
        // A leftover /tigerduck/debug-clock entry from a phone debug build (or
        // a sideloaded debug companion) must not be applied to a release watch
        // — there's no UI to clear it from the Wear side.
        if (!BuildConfig.DEBUG) return
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearProtocol.DebugClock.PATH) continue
            val map = DataMapItem.fromDataItem(item).dataMap
            val cleared = map.getBoolean(WearProtocol.DebugClock.KEY_CLEARED, false)
            val override = if (cleared) {
                null
            } else {
                ClockOverride(
                    instantMillis = map.getLong(WearProtocol.DebugClock.KEY_INSTANT),
                    frozen = map.getBoolean(WearProtocol.DebugClock.KEY_FROZEN, true),
                    savedAtRealMillis = map.getLong(WearProtocol.DebugClock.KEY_SAVED_AT),
                )
            }
            store.save(override)
            AppClock.setOverride(override)
            // Tile + complication cache their last-rendered frame; nudging
            // both forces them to re-resolve against the new clock right
            // away instead of waiting for the next system-driven refresh.
            NextClassTileService.requestUpdate(applicationContext)
            NextClassComplicationService.requestUpdate(applicationContext)
            Log.d(TAG, "applied override cleared=$cleared")
        }
    }

    private companion object {
        const val TAG = "WearDebugClock"
    }
}
