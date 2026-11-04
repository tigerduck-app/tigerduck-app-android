package org.ntust.app.tigerduck.sync.reconcile

/**
 * The full sync's steps after its fetch, in the order they run: a category's first sync, which
 * queues this phone's values as the oldest before any step reads what waits (LWW D13), then the
 * marks, the course ignores, the colors and names, the holidays, then the course list with the
 * roster requests. The ignores go before the colors, as they always ran. A course reset made
 * elsewhere is no step of its own: its tombstones take the courses it cleared, in the course list
 * (LWW D10). `SynchronizerOrderTest` fails on a swap.
 */
object Reconcilers {
    fun inOrder(
        firstSync: Reconciler,
        marks: Reconciler,
        ignores: Reconciler,
        overrides: Reconciler,
        holidays: Reconciler,
        roster: Reconciler,
    ): List<Reconciler> = listOf(firstSync, marks, ignores, overrides, holidays, roster)
}
