package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.sync.AckClock

/**
 * One pull's reading of what this phone keeps over its snapshot, field by field (LWW D12,
 * `tigersync-merge`, "A client keeps unsent edits over the snapshot"): a field ([org.ntust.app.tigerduck.sync.SyncField])
 * the outbox holds an edit of ([held], read once for the pull), or one whose last acknowledged
 * write answered at a clock not earlier than the snapshot's [serverHlc], which the snapshot may
 * predate. Every other field, and its absence, is the snapshot's. Only a snapshot that carries a
 * `server_hlc`, as `/v4`'s does, is read this way.
 *
 * The acknowledgments are read as each field is asked about: a write answered while the pull runs
 * holds its field from then on. A step that writes under a lock its channel's own writes take
 * also reads that channel's waiting edits there, so an edit kept after [held] was read holds too.
 */
class PullHolds(val serverHlc: Long, private val held: Set<String>, private val ack: AckClock) {

    /** Whether this phone keeps its own value of [field]. */
    fun keeps(field: String): Boolean = field in held || acked(field)

    /** Every field this phone keeps now: [held], and each acknowledged since the snapshot began. */
    fun fields(): Set<String> = held + ack.acknowledgedSince(serverHlc)

    private fun acked(field: String): Boolean = ack.lastAck(field)?.let { it >= serverHlc } == true
}
