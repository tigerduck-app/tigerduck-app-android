package org.ntust.app.tigerduck.sync.outbox

import org.ntust.app.tigerduck.auth.session.LateAccountScoped
import org.ntust.app.tigerduck.shared.account.SignIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every unsent edit, in its channel ([OutboxChannel]): each channel registers itself in its own
 * file, and this sends them all in one pinned order, [OutboxChannelId]'s, which is the order the
 * syncs sent the queues in before there was one outbox.
 *
 * One of the school's stores ([org.ntust.app.tigerduck.auth.session.SchoolStores.late], before
 * DataCache): the queues' files are DataCache's, and its clearing takes them; what a sign-out
 * leaves to the outbox is what its channels hold in memory for the account.
 */
@Singleton
class Outbox @Inject constructor(channels: Set<@JvmSuppressWildcards OutboxChannel>) : LateAccountScoped {

    private val channels: List<OutboxChannel> = channels.sortedBy { it.id }.also { sorted ->
        val ids = sorted.map { it.id }
        require(ids.distinct() == ids) { "two outbox channels share an id: $ids" }
    }

    /** Sends what waits in every channel under [signIn], one channel after the other, in [OutboxChannelId] order. */
    suspend fun drain(signIn: SignIn) {
        for (channel in channels) {
            if (!signIn.isCurrent) return
            channel.drain(signIn)
        }
    }

    /** A course upload has landed: an edit parked until the backend had the course gets one more try. */
    suspend fun courseUploadLanded(signIn: SignIn) {
        for (channel in channels) {
            if (!signIn.isCurrent) return
            channel.requeueParked(signIn)
        }
    }

    /** Whether an edit of [field] waits in any channel: a pull keeps this phone's value of it (LWW D12). */
    suspend fun holds(field: String): Boolean = channels.any { field in it.heldFields() }

    /** Every field an edit waits for, in any channel: [holds] for many fields at once. */
    suspend fun heldFields(): Set<String> = channels.flatMapTo(HashSet()) { it.heldFields() }

    /** At the school's sign-out: each channel forgets its parked-retry flags and the attempts it counted in memory. */
    override suspend fun forgetAccount() {
        for (channel in channels) channel.forgetMemory()
    }
}
