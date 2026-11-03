package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInBound

/**
 * What the course colors and names' queue ([PendingCourseOverrides]) keeps in memory beside its
 * file: the versions it gave out, and when each key last changed here or was confirmed by the
 * backend. Read and written under the queue's lock. In memory, as a reservation is: neither
 * outlives the process, and only a sync running in this process can be older than a change.
 */
internal class CourseOverrideMemory(private val clock: () -> Long) {
    // The last version given out; see [nextSeq].
    private var lastSeq = 0L

    // The version last recorded under each key, kept past the entry's send: a batch reserved
    // before a change that has since landed is still the older, and the file no longer says so.
    private val latestSeq = HashMap<String, Long>()

    // When each key last changed here or was confirmed by the backend. A snapshot fetched before
    // then may predate the change, so its value for the key is not taken. Its sign-in's alone: one
    // that has ended reads as none, and writes nothing for the next.
    private val touchedAt = SignInBound<Map<String, Long>>(emptyMap())

    /**
     * Later than every version an entry still [waiting] carries and than every one given out in
     * this process, reservations included. A version is only ever compared with the entries
     * waiting when it is used, and a reservation does not outlive the process, so it need not keep
     * growing across restarts beyond theirs: the clock where it is ahead, else one past the last.
     */
    fun nextSeq(waiting: Collection<PendingCourseOverride>): Long {
        val floor = maxOf(lastSeq, waiting.maxOfOrNull { it.seq } ?: 0L)
        lastSeq = maxOf(clock(), floor + 1)
        return lastSeq
    }

    /** Whether a change of [key] newer than [seq] was recorded, [waitingSeq] the one waiting's if any. */
    fun outbids(key: String, waitingSeq: Long?, seq: Long): Boolean = maxOf(waitingSeq ?: 0L, latestSeq[key] ?: 0L) > seq

    /** [key]'s last change is at [seq]: kept, or this phone's own. */
    fun recordedAt(key: String, seq: Long) {
        latestSeq[key] = seq
    }

    /** [keys] changed here, or were confirmed by the backend, at [atMs]; noted while [signIn] stands. */
    fun touched(keys: Collection<String>, atMs: Long, signIn: SignIn) {
        signIn.ifCurrent { touchedAt.set(touchedAt.value + keys.associateWith { atMs }, signIn) }
    }

    /** [keys] are no longer this phone's to hold against a snapshot: the server's were taken. */
    fun untouched(keys: Collection<String>, signIn: SignIn) {
        signIn.ifCurrent { touchedAt.set(touchedAt.value - keys.toSet(), signIn) }
    }

    /** The keys changed or confirmed at [fetchedAtMs] or after, which a snapshot fetched then predates. */
    fun touchedSince(fetchedAtMs: Long): Set<String> = touchedAt.value.filterValues { it >= fetchedAtMs }.keys
}
