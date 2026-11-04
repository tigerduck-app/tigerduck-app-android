package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.sync.AckClock

/**
 * One pull's reading of what this phone keeps over its snapshot, field by field (LWW D12,
 * `tigersync-merge`, "A client keeps unsent edits over the snapshot"), beside the edits waiting in
 * the outbox: a field ([org.ntust.app.tigerduck.sync.SyncField]) whose last acknowledged write
 * answered at a clock not earlier than the snapshot's [serverHlc], which the snapshot may predate.
 * Every other field, and its absence, is the snapshot's, but one whose edit waits.
 *
 * The acknowledgments are read as each field is asked about: a write answered while the pull runs
 * holds its field from then on. The waiting edits are each step's own to read, in the step that
 * writes, under the lock its channel's own writes take, so an edit kept while the pull runs holds
 * its field too.
 */
class PullHolds(val serverHlc: Long, private val ack: AckClock) {

    /** Whether this phone keeps its own value of [field], its last write acknowledged since the snapshot began. */
    fun keeps(field: String): Boolean = acked(field)

    /** Every field this phone keeps now for an acknowledged write: each acknowledged since the snapshot began. */
    fun fields(): Set<String> = ack.acknowledgedSince(serverHlc)

    private fun acked(field: String): Boolean = ack.lastAck(field)?.let { it >= serverHlc } == true
}
