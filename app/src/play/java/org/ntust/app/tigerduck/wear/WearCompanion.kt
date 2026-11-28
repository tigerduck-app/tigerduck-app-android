package org.ntust.app.tigerduck.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Whether this flavor ships the Wear OS app: play does, so Settings offers what concerns the watch. */
object WearCompanion {
    const val SHIPS = true

    /**
     * Whether a Wear OS watch is connected to this phone now, through Play services' Wear OS layer.
     * Throws where that layer is missing, which [WatchSeen] takes as no watch.
     */
    suspend fun hasConnectedWatch(context: Context): Boolean =
        Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
}
