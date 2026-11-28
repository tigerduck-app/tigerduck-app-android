package org.ntust.app.tigerduck.wear

import android.content.Context

/**
 * Whether this flavor ships the Wear OS app: F-Droid's does not (`:wear` depends on
 * play-services-wearable), so Settings offers nothing that concerns a watch.
 */
object WearCompanion {
    const val SHIPS = false

    /** No Play services to ask, and no watch app to pair: never a watch. */
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun hasConnectedWatch(context: Context): Boolean = false
}
