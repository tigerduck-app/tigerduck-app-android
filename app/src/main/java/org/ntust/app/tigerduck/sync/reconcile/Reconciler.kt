package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.sync.SyncPass

/**
 * One step of a full sync after its fetch: it applies the snapshot ([SyncPass.result]) to this
 * phone's data for what it reconciles. Returns whether it took the backend's data for that, which
 * the `Synchronizer` records for the categories with a switch of their own.
 */
interface Reconciler {
    suspend fun reconcile(pass: SyncPass): Boolean
}
