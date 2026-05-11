package org.ntust.app.tigerduck.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.shared.WearProtocol
import java.util.concurrent.TimeUnit

object SyncRequester {

    /**
     * If the cached snapshot is older than [maxAgeMs] (or absent), sends a sync
     * request to every connected node. Failures are silent — the staleness
     * banner is the user-facing signal.
     */
    suspend fun maybeRequest(
        context: Context,
        snapshot: WatchSnapshot,
        maxAgeMs: Long = TimeUnit.MINUTES.toMillis(10),
    ) {
        val syncedAt = snapshot.syncedAtMs
        val now = System.currentTimeMillis()
        if (syncedAt != null && now - syncedAt < maxAgeMs) return

        try {
            val nodeIds = Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
            for (nodeId in nodeIds) {
                Wearable.getMessageClient(context)
                    .sendMessage(nodeId, WearProtocol.SyncRequest.PATH, ByteArray(0))
                    .await()
                Log.d(TAG, "sync request sent to $nodeId")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sync request failed: ${t.message}")
        }
    }

    private const val TAG = "WearBridge"
}
