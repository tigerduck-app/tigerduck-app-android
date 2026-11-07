package org.ntust.app.tigerduck.wear

/**
 * Whether this flavor ships the Wear OS app: F-Droid's does not (`:wear` depends on
 * play-services-wearable), so Settings offers nothing that concerns a watch.
 */
object WearCompanion {
    const val SHIPS = false
}
