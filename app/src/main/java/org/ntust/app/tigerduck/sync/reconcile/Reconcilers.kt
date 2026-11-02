package org.ntust.app.tigerduck.sync.reconcile

/**
 * The full sync's steps after its fetch, in the order they run: the marks, the course ignores,
 * the colors and names, a course reset made elsewhere, the holidays, then the course list with
 * the roster requests. The reset goes before the course list, which then reconciles what it left;
 * the ignores before the colors, as they always ran. `SynchronizerOrderTest` fails on a swap.
 */
object Reconcilers {
    fun inOrder(
        marks: Reconciler,
        ignores: Reconciler,
        overrides: Reconciler,
        reset: Reconciler,
        holidays: Reconciler,
        roster: Reconciler,
    ): List<Reconciler> = listOf(marks, ignores, overrides, reset, holidays, roster)
}
