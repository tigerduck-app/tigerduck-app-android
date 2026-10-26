package org.ntust.app.tigerduck.mail.index

import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailRow
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [MailSearchIndex] in memory, for tests off the device: the same rules as the SQLite one, which
 * MailSearchIndexTest holds it to, with a match ignoring case for ASCII as SQLite's LIKE does.
 */
class InMemoryMailSearchIndex : MailSearchIndex {
    private var owner: String? = null
    private val validities = mutableMapOf<String, Long>()
    private val mail = mutableMapOf<String, MutableMap<Long, MailSearchIndex.Entry>>()

    /** Every write the index refused, as a test reads what a run that outlived its account tried. */
    var refusedWrites = 0
        private set

    /** Fires once [clear] has emptied it: a test waits on the clear a sign-in runs on its own. */
    @Volatile var onClear: (() -> Unit)? = null

    /** Fires once a cleanup of an account that left has run, whether it emptied anything or not. */
    @Volatile var onCleanup: (() -> Unit)? = null

    /** Fires as a search starts: a test changes the server between the index's answer and its use. */
    @Volatile var onSearch: (() -> Unit)? = null

    /** The thread of every call, as a test reads what ran on the main thread; SQLite would block it. */
    val callers = ConcurrentLinkedQueue<Thread>()

    private fun called() {
        callers += Thread.currentThread()
    }

    @Synchronized
    override fun uidValidity(owner: String, folder: String): Long? {
        called()
        return if (this.owner == owner) validities[folder] else null
    }

    @Synchronized
    override fun open(owner: String, folder: String, uidValidity: Long, stillCurrent: () -> Boolean): Boolean {
        called()
        if (!stillCurrent()) {
            refusedWrites++
            return false
        }
        if (this.owner != owner) {
            clear()
            this.owner = owner
        }
        if (validities[folder] != uidValidity) {
            mail.remove(folder)
            validities[folder] = uidValidity
        }
        return true
    }

    @Synchronized
    override fun uids(owner: String, folder: String): Set<Long> {
        called()
        return if (this.owner == owner) mail[folder]?.keys?.toSet().orEmpty() else emptySet()
    }

    @Synchronized
    override fun add(
        owner: String,
        folder: String,
        uidValidity: Long,
        entries: List<MailSearchIndex.Entry>,
        stillCurrent: () -> Boolean,
    ): Boolean {
        called()
        if (!stillCurrent() || this.owner != owner || validities[folder] != uidValidity) {
            refusedWrites++
            return false
        }
        val box = mail.getOrPut(folder) { mutableMapOf() }
        entries.forEach { box[it.uid] = it }
        return true
    }

    @Synchronized
    override fun remove(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>, stillCurrent: () -> Boolean) {
        called()
        if (!stillCurrent()) {
            refusedWrites++
            return
        }
        if (this.owner == owner && validities[folder] == uidValidity) mail[folder]?.keys?.removeAll(uids.toSet())
    }

    @Synchronized
    override fun search(owner: String, folder: String, uidValidity: Long, query: String): List<Long> {
        called()
        onSearch?.invoke()
        if (query.isEmpty() || this.owner != owner || validities[folder] != uidValidity) return emptyList()
        fun String.holds() = asciiLower().contains(query.asciiLower())
        return mail[folder].orEmpty().values
            .filter { it.sender.holds() || it.subject.holds() || it.body.holds() }
            .map { it.uid }
            .sortedDescending()
    }

    @Synchronized
    override fun summaries(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>): Map<Long, MailSummary> {
        called()
        if (this.owner != owner || validities[folder] != uidValidity) return emptyMap()
        val box = mail[folder].orEmpty()
        return uids.mapNotNull { box[it]?.summary }.associateBy { it.uid }
    }

    @Synchronized
    override fun allMail(owner: String): List<MailRow> {
        called()
        if (this.owner != owner) return emptyList()
        return mail.flatMap { (folder, box) -> box.values.map { MailRow(folder, it.summary) } }
    }

    @Synchronized
    override fun updateFlags(
        owner: String,
        folder: String,
        uidValidity: Long,
        flags: Map<Long, MailFlags>,
        stillCurrent: () -> Boolean,
    ) {
        called()
        if (!stillCurrent()) {
            refusedWrites++
            return
        }
        if (this.owner != owner || validities[folder] != uidValidity) return
        val box = mail[folder] ?: return
        flags.forEach { (uid, now) ->
            val entry = box[uid] ?: return@forEach
            if (now.deleted) box.remove(uid) else box[uid] = entry.copy(summary = entry.summary.copy(flags = now))
        }
    }

    @Synchronized
    override fun clear() {
        called()
        owner = null
        validities.clear()
        mail.clear()
        onClear?.invoke()
        onCleanup?.invoke()
    }

    @Synchronized
    override fun clearUnless(keep: (owner: String?) -> Boolean) {
        called()
        if (!keep(owner)) clear() else onCleanup?.invoke()
    }

    /** What the entries would take: their text, roughly, as a test only compares it to none. */
    @Synchronized
    override fun sizeBytes(): Long =
        mail.values.sumOf { box -> box.values.sumOf { (it.sender.length + it.subject.length + it.body.length).toLong() } }

    private fun String.asciiLower() = map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
}

/** Records what was asked of the background download, which a test never runs. */
class RecordingIndexScheduler : MailIndexScheduler {
    var requests = 0
        private set

    /** Runs asked for after whatever run is under way ([MailIndexScheduler.requestAgain]). */
    var followUps = 0
        private set

    /** Every request, follow-up and cancel, in order, from whatever thread. */
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The thread of every cancel: the real one is WorkManager's, called off the main thread. */
    val cancelThreads = ConcurrentLinkedQueue<Thread>()

    /** Fires inside [requestAgain], before it is counted: a test acts while WorkManager is asked. */
    @Volatile var onRequestAgain: (() -> Unit)? = null
    var cancels = 0
        private set

    override fun request() {
        requests++
        events += "request"
    }

    override fun requestAgain() {
        onRequestAgain?.invoke()
        followUps++
        events += "followUp"
    }

    override fun cancel() {
        cancelThreads += Thread.currentThread()
        events += "cancel"
        cancels++
    }
}
