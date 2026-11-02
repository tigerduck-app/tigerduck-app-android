package org.ntust.app.tigerduck.sync.outbox

import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * Where a lane keeps its entries: the queue's own file, through today's `DataCache` or preferences
 * calls, so no file, key or shape changes. The list's order is the order the lane sends in, oldest
 * first.
 */
interface LaneStore<E> {
    suspend fun load(): List<E>

    /** Writes [entries] in place of what is there; false when nothing was written, as under an ended [signIn]. */
    suspend fun save(entries: List<E>, signIn: SignIn): Boolean
}

/**
 * One channel's rules for its lane, each today's for its queue (spec §5.3, "keeps the attempts,
 * the backoff"): the field each entry edits ([field], from [org.ntust.app.tigerduck.sync.SyncField]);
 * the refusals after which an entry parks ([maxAttempts]); where they are counted ([attempts]);
 * whether a failure that says nothing about the edit ends the drain ([stopsOnRetry]), as a queue
 * whose next send would fail the same way stops today; and how an entry an older build wrote with
 * no time is given one ([untimed], X1).
 */
class LanePolicy<E>(
    val field: (E) -> String,
    val maxAttempts: Int,
    val attempts: AttemptBook<E>,
    val stopsOnRetry: Boolean = false,
    val untimed: UntimedEntries<E>? = null,
) {
    init {
        require(maxAttempts > 0) { "an entry needs at least one attempt" }
    }
}

/**
 * Where a lane counts an entry's refusals: on disk, in the entry, where the queue counts them there
 * today ([OnDiskAttempts]); else in memory, for this process ([InMemoryAttempts]).
 */
interface AttemptBook<E> {
    /** The refusals counted against [entry]: it is parked at the lane's `maxAttempts`. */
    fun attempts(entry: E): Int

    /**
     * [entry] with [attempts] counted against it, [refused] when a refusal brought it there rather
     * than a requeue: the entry the lane stores in its place.
     */
    fun counted(entry: E, attempts: Int, refused: Boolean): E

    /** Whether [a] and [b] are one edit, whatever is counted against each. */
    fun sameEdit(a: E, b: E): Boolean

    /** Forgets what is counted in memory; at a sign-out. */
    fun forget()
}

/**
 * Counts kept in the entry, in the queue's own file: [attempts] reads them, [counted] writes them
 * (and a refusal's time, where the queue keeps one), and [sameEdit] tells one edit from another
 * whatever their counts say. Nothing to forget: the sign-out clears the file.
 */
class OnDiskAttempts<E>(
    private val attempts: (E) -> Int,
    private val counted: (entry: E, attempts: Int, refused: Boolean) -> E,
    private val sameEdit: (E, E) -> Boolean,
) : AttemptBook<E> {
    override fun attempts(entry: E): Int = attempts.invoke(entry)

    override fun counted(entry: E, attempts: Int, refused: Boolean): E = counted.invoke(entry, attempts, refused)

    override fun sameEdit(a: E, b: E): Boolean = sameEdit.invoke(a, b)

    override fun forget() = Unit
}

/**
 * Counts kept for this process, for a queue that keeps none on disk: one per [field], for the edit
 * last counted there, so a newer edit of the field starts from none and nothing outlives the
 * fields the queue has known. An edit is the entry itself ([sameEdit] is equality).
 */
class InMemoryAttempts<E>(private val field: (E) -> String) : AttemptBook<E> {
    private val counts = HashMap<String, Pair<E, Int>>()

    override fun attempts(entry: E): Int = synchronized(counts) {
        counts[field(entry)]?.takeIf { it.first == entry }?.second ?: 0
    }

    override fun counted(entry: E, attempts: Int, refused: Boolean): E {
        synchronized(counts) { counts[field(entry)] = entry to attempts }
        return entry
    }

    override fun sameEdit(a: E, b: E): Boolean = a == b

    override fun forget() = synchronized(counts) { counts.clear() }
}

/**
 * How a lane gives an entry an older build wrote with no time its old-entry time (X1): [timeOf]
 * reads an entry's time, null for none; [withTime] sets it; [oldEntryTimeMs] is
 * [OldEntryTime.oldEntryTimeMs] of the lane's file, read before the lane writes it.
 */
class UntimedEntries<E>(
    val timeOf: (E) -> Long?,
    val withTime: (E, Long) -> E,
    val oldEntryTimeMs: () -> Long,
)
