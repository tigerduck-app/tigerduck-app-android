package org.ntust.app.tigerduck.debug

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
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
        val request = PutDataMapRequest.create(PATH).apply {
            if (override == null) {
                dataMap.putBoolean(KEY_CLEARED, true)
                dataMap.putLong(KEY_VERSION, System.currentTimeMillis())
            } else {
                dataMap.putBoolean(KEY_CLEARED, false)
                dataMap.putLong(KEY_INSTANT, override.instantMillis)
                dataMap.putBoolean(KEY_FROZEN, override.frozen)
                dataMap.putLong(KEY_SAVED_AT, override.savedAtRealMillis)
                dataMap.putLong(KEY_VERSION, System.currentTimeMillis())
            }
        }.asPutDataRequest().setUrgent()

        try {
            Wearable.getDataClient(context).putDataItem(request).await()
            Log.d(TAG, "push ok: cleared=${override == null}")
        } catch (t: Throwable) {
            Log.w(TAG, "push failed: ${t.message}")
        }
    }

    companion object {
        const val PATH = "/tigerduck/debug-clock"
        const val KEY_CLEARED = "cleared"
        const val KEY_INSTANT = "instant_millis"
        const val KEY_FROZEN = "frozen"
        const val KEY_SAVED_AT = "saved_at_real_millis"
        // Bumped on every push so the data layer treats two equal-payload
        // overrides as different items and re-delivers them. Without this,
        // toggling on→off→on with the same instant would silently no-op.
        const val KEY_VERSION = "version"
        private const val TAG = "WearDebugClock"
    }
}
