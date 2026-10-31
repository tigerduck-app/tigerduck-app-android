package org.ntust.app.tigerduck.mail.index

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.model.MailSummary

/** Runs [block] on the connection the mail screens share, as MailRepository's own calls do. */
internal interface MailSessionRunner {
    suspend fun <T> run(stillWanted: () -> Boolean, block: (MailSession) -> T): T
}

/**
 * How far a download into the search index has come: [done] of the [total] mails it lacked, or
 * [COUNTING] while it lists every folder to find that total.
 */
data class MailIndexProgress(
    val done: Int,
    val total: Int?,
    /**
     * Whether the download is under way. False for one that stopped short -- the network, a
     * folder recreated meanwhile -- and left mail out of search, until one gets through.
     */
    val running: Boolean = true,
) {
    companion object {
        /** Under way, with no total yet: every folder listed, planned and its flags asked for first. */
        val COUNTING = MailIndexProgress(0, null)
    }
}

/**
 * Brings the [MailSearchIndex] up to the server's mail, a folder at a time and [BATCH] mails at a
 * time. Each batch holds the shared connection once, so a page the user opens waits on one batch
 * at most. All of it on the IO dispatcher, its reads and writes of the index included: a search
 * calls it from the main thread.
 */
internal class MailIndexer(
    private val index: MailSearchIndex,
    private val sessions: MailSessionRunner,
    /** Who the index is for now; null while signed out. */
    private val owner: () -> Owner?,
    /**
     * Whether the index is still [Owner]'s: no sign-in, sign-out or deletion of what it holds since
     * it was read, which a run that outlives one has no business writing past.
     */
    private val stillOwner: (Owner) -> Boolean,
) {
    /**
     * Whom a run downloads for: the signed-in account's [address], read together with the
     * [generation] it signed in at, so a run never pairs one account's address with the sign-in
     * of the next, whose credentials its requests would then go out under; and the index as it
     * was then, which deleting what it holds moves on to the next [epoch].
     */
    data class Owner(val address: String, val generation: Int, val epoch: Int)

    /** What [folder]'s index lacks, newest first, as [plan] found it for [owner] under [uidValidity]. */
    class Plan internal constructor(
        val folder: String,
        internal val owner: Owner,
        internal val uidValidity: Long,
        val missing: List<Long>,
        /** How many more mails the index lacks than [missing] holds: the older ones, under newOnly. */
        internal val leftOut: Int,
    )

    /**
     * Opens [folder]'s index at the server's UIDVALIDITY, drops the mail the server no longer has,
     * and says what it lacks, newest first: with [newOnly], only mail newer than any it has, which a
     * search catches up on without waiting for the first download's backlog. Null for no account,
     * or one that left meanwhile.
     */
    suspend fun plan(folder: String, newOnly: Boolean = false, stillWanted: () -> Boolean = { true }): Plan? = withContext(Dispatchers.IO) {
        val owner = owner() ?: return@withContext null
        val current = { stillWanted() && stillOwner(owner) }
        // The index brought to the server's list in the same hold of the connection that read it,
        // as [fill]'s batches are filed: mail a search downloads meanwhile, in a hold of its own,
        // is never dropped as gone from a list read before it came, nor the folder opened again
        // at a UIDVALIDITY it has been recreated past.
        sessions.run(current) { s ->
            // The UIDs first: the session then answers the UIDVALIDITY from the folder they opened.
            val onServer = s.undeletedUids(folder)
            val uidValidity = s.uidValidity(folder)
            if (!index.open(owner.address, folder, uidValidity, current)) return@run null
            val indexed = index.uids(owner.address, folder)
            index.remove(owner.address, folder, uidValidity, indexed - onServer.toSet(), current)
            val newest = if (newOnly) indexed.maxOrNull() ?: 0L else 0L
            val lacking = onServer.filter { it !in indexed }
            val missing = lacking.filter { it > newest }.sortedDescending()
            Plan(folder, owner, uidValidity, missing, leftOut = lacking.size - missing.size)
        }
    }

    /**
     * Downloads [plan]'s mail into the index, [limit] mails at most, telling [onAdded] how many
     * each batch added. Says whether it got through all it was given.
     */
    suspend fun fill(
        plan: Plan,
        limit: Int = Int.MAX_VALUE,
        stillWanted: () -> Boolean = { true },
        onAdded: (Int) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val current = { stillWanted() && stillOwner(plan.owner) }
        for (batch in plan.missing.take(limit).chunked(BATCH)) {
            // Read and filed in one hold of the connection. A mark-read or a move holds it too, so
            // it reaches the server before the batch reads the mail, or the index once the batch
            // is in: never in between, where the batch's older answer would undo it.
            val added = sessions.run(current) { s ->
                // Recreated since the plan, the folder's UIDs name other mail: the next run plans
                // it again, which empties its index.
                if (s.uidValidity(plan.folder) != plan.uidValidity) return@run false
                val texts = s.fetchSearchTexts(plan.folder, batch)
                val entries = s.fetchByUids(plan.folder, batch).map {
                    MailSearchIndex.Entry(it.uid, senderOf(it), it.subject, texts[it.uid].orEmpty(), it)
                }
                index.add(plan.owner.address, plan.folder, plan.uidValidity, entries, current)
            }
            if (!added) return@withContext false
            // The batch is done with, mail since deleted included, which the next run will not see.
            onAdded(batch.size)
        }
        plan.missing.size <= limit
    }

    /**
     * Brings the flags of the mail [plan]'s folder has downloaded to the server's: a mail read,
     * answered or deleted on another device or the web shows so on the phone's copy too. Says
     * whether it got through: not for a folder recreated since the plan, which the next run plans
     * again.
     */
    suspend fun refreshFlags(plan: Plan, stillWanted: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        val current = { stillWanted() && stillOwner(plan.owner) }
        val downloaded = index.uids(plan.owner.address, plan.folder).toList()
        for (chunk in downloaded.chunked(FLAGS_BATCH)) {
            // Read and filed in one hold of the connection, as [fill]'s batches are, and for the
            // same UIDVALIDITY: the UIDs of a folder recreated since name other mail.
            val applied = sessions.run(current) { s ->
                if (s.uidValidity(plan.folder) != plan.uidValidity) return@run false
                index.updateFlags(plan.owner.address, plan.folder, plan.uidValidity, s.refreshFlags(plan.folder, chunk), current)
                true
            }
            if (!applied) return@withContext false
        }
        true
    }

    /**
     * [plan] and [fill] in one: what a search catches up on before it looks. Says whether the
     * folder's index then holds every mail the server had, the older ones [newOnly] leaves out
     * included.
     */
    suspend fun update(
        folder: String,
        limit: Int = Int.MAX_VALUE,
        newOnly: Boolean = false,
        stillWanted: () -> Boolean = { true },
    ): Boolean {
        val plan = plan(folder, newOnly, stillWanted) ?: return false
        return fill(plan, limit, stillWanted) && plan.leftOut == 0
    }

    companion object {
        /**
         * Mails fetched per hold of the connection: two FETCHes, so a page the user opens waits a
         * second or two at most.
         */
        const val BATCH = 20

        /** Mails whose flags one FETCH asks for: flags are a few bytes a mail. */
        const val FLAGS_BATCH = 500

        /** The sender as a search reads it, the name and the address both. */
        fun senderOf(summary: MailSummary): String =
            listOfNotNull(summary.from?.name, summary.from?.address).joinToString(" ")
    }
}
