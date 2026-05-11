package org.ntust.app.tigerduck.debug

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the phone's debug clock override to the paired watch via the
 * Wearable Data Layer. Play flavor only — fdroid has a no-op stub at the
 * same FQN.
 */
@Singleton
class WearDebugClockBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Send the override to the watch. `null` means "clear the override on the
     * watch". Safe to call repeatedly — same payload no-ops at the data-layer
     * level.
     */
    suspend fun push(override: ClockOverride?) {
        val request = PutDataMapRequest.create(WearProtocol.DebugClock.PATH).apply {
            if (override == null) {
                dataMap.putBoolean(WearProtocol.DebugClock.KEY_CLEARED, true)
                dataMap.putLong(WearProtocol.DebugClock.KEY_VERSION, System.currentTimeMillis())
            } else {
                dataMap.putBoolean(WearProtocol.DebugClock.KEY_CLEARED, false)
                dataMap.putLong(WearProtocol.DebugClock.KEY_INSTANT, override.instantMillis)
                dataMap.putBoolean(WearProtocol.DebugClock.KEY_FROZEN, override.frozen)
                dataMap.putLong(WearProtocol.DebugClock.KEY_SAVED_AT, override.savedAtRealMillis)
                dataMap.putLong(WearProtocol.DebugClock.KEY_VERSION, System.currentTimeMillis())
            }
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "push ok: cleared=${override == null}")
        } catch (t: Throwable) {
            Log.w(TAG, "push failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "WearDebugClock"
    }
}
