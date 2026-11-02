// The course list's two outbox channels, the adds (PendingCourseAdds) and the deletes
// (PendingCourseDeletes): their entries, the stores their lanes keep them in, which are the files
// the queues kept before they were channels, and what the two read of the outbox and the policy.

package org.ntust.app.tigerduck.data

import android.util.Log
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.SyncPolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.util.logSummary

/** A course added by hand to [semester] whose upload waits, with the moment it was asked, if it has one. */
internal data class CourseAdd(val semester: String, val courseNo: String, val addedAtMs: Long?) {
    val field: String get() = membershipField(semester, courseNo)

    /**
     * Where the add's moment stood in the times file in the read that gave this add, the one
     * read of both files under the lane's lock ([CourseAddsStore.load]): `/v3`'s body lists its
     * adds in that order. Last when it has none or was not read so. Not part of the edit, so
     * neither compared nor copied.
     */
    var momentAt: Int = Int.MAX_VALUE
        private set

    /** This add, as read with its moment at [at] in the times file. */
    fun readWithMomentAt(at: Int?): CourseAdd = also { momentAt = at ?: Int.MAX_VALUE }
}

/** A course deleted from [semester] whose DELETE waits, with the moment it was asked. */
internal data class CourseDelete(val semester: String, val courseNo: String, val queuedAt: Long) {
    val field: String get() = membershipField(semester, courseNo)
}

/** The field an add, a hide and a removal of [courseNo] in [semester] all edit. */
internal fun membershipField(semester: String, courseNo: String): String =
    SyncField.membership(SyncField.courseKey(semester, courseNo))

/** [this] by term, each term's in order, as the queue files hold them. */
private fun <E> List<E>.byTerm(semester: (E) -> String): Map<String, List<E>> = groupBy(semester)

/**
 * The adds' lane store: `pending_course_adds.json` (term to course numbers) and, beside it,
 * `pending_course_add_times.json` (term to course number to moment), as the queue kept them, so an
 * older install's file, whose adds have no moment, reads as it is. The adds go in the files' order.
 */
internal class CourseAddsStore(private val dataCache: DataCache) : LaneStore<CourseAdd> {
    override suspend fun load(): List<CourseAdd> {
        val times = dataCache.loadPendingCourseAddTimes()
        return dataCache.loadPendingCourseAdds().flatMap { (semester, nos) ->
            val order = times[semester].orEmpty().keys.withIndex().associate { (at, courseNo) -> courseNo to at }
            nos.distinct().map { CourseAdd(semester, it, times[semester]?.get(it)).readWithMomentAt(order[it]) }
        }
    }

    /**
     * A moment is written before its add and goes after it, so an add with a moment is never read
     * without it: sent unstamped, it would count from when it arrives and win over a hide made
     * after it elsewhere. A file the change leaves as it is is not written.
     */
    override suspend fun save(entries: List<CourseAdd>, signIn: SignIn): Boolean {
        val before = dataCache.loadPendingCourseAdds()
        val timesBefore = dataCache.loadPendingCourseAddTimes()
        val after = entries.byTerm { it.semester }.mapValues { (_, adds) -> adds.map { it.courseNo } }
        val timesAfter = momentsInPlace(entries, timesBefore)
        val added = after.any { (semester, nos) -> nos.any { it !in before[semester].orEmpty() } }
        suspend fun saveAdds() = after == before || dataCache.savePendingCourseAdds(after, signIn)
        suspend fun saveTimes() = timesAfter == timesBefore || dataCache.savePendingCourseAddTimes(timesAfter, signIn)
        return if (added) saveTimes() && saveAdds() else saveAdds() && saveTimes()
    }
}

/**
 * [entries]' moments as the times file keeps them, where [before] has them: each where it was
 * first kept and a new one after the rest of its term's, a term's where it first had one, as the
 * queue has always written that file. The file's order is not the adds file's (an add asked first
 * with no moment and later with one is behind the others in it), and `/v3`'s upload lists its
 * adds in it.
 */
private fun momentsInPlace(entries: List<CourseAdd>, before: Map<String, Map<String, Long>>): Map<String, Map<String, Long>> {
    val now = entries.filter { it.addedAtMs != null }.byTerm { it.semester }
    val kept = LinkedHashMap<String, Map<String, Long>>()
    for (semester in before.keys + now.keys) {
        val adds = now[semester] ?: continue
        val times = LinkedHashMap<String, Long>()
        for (courseNo in before[semester].orEmpty().keys) adds.firstOrNull { it.courseNo == courseNo }?.let { times[courseNo] = it.addedAtMs!! }
        for (add in adds) if (add.courseNo !in times) times[add.courseNo] = add.addedAtMs!!
        kept[semester] = times
    }
    return kept
}

/** The deletes' lane store: `pending_course_deletes.json` (term to course number to moment), read through [load]. */
internal class CourseDeletesStore(
    private val dataCache: DataCache,
    private val load: suspend () -> Map<String, Map<String, Long>>,
) : LaneStore<CourseDelete> {
    override suspend fun load(): List<CourseDelete> = deletesOf(load.invoke())

    override suspend fun save(entries: List<CourseDelete>, signIn: SignIn): Boolean =
        dataCache.savePendingCourseDeletes(deletesByTerm(entries), signIn)
}

/** The deletes [byTerm] holds (term to course number to moment), in its order. */
internal fun deletesOf(byTerm: Map<String, Map<String, Long>>): List<CourseDelete> =
    byTerm.flatMap { (semester, entries) -> entries.map { (courseNo, at) -> CourseDelete(semester, courseNo, at) } }

/** [deletes] as the file holds them: term to course number to moment, each term's in order. */
internal fun deletesByTerm(deletes: List<CourseDelete>): Map<String, Map<String, Long>> =
    deletes.byTerm { it.semester }.mapValues { (_, ofTerm) -> ofTerm.associate { it.courseNo to it.queuedAt } }

/**
 * What the course list's channels take from the outbox and the sync policy. Built with nothing,
 * as a test that builds a queue alone does, it is `/v3`'s queue as it was: no policy read, no last
 * writer wins, its acknowledgments its own, and sends run on where their caller resumes them.
 *
 * - [policies] are read once per question; none is `/v3`'s.
 * - [ack] is the outbox's [AckClock], [scope] the application's, where a send runs to its end.
 * - [oldEntryTime] gives an add an older build kept with no moment its old-entry time (X1).
 * - [outbox] is told when a course upload has landed.
 */
internal class MembershipOutbox(
    private val policies: SyncPolicies? = null,
    ack: AckClock? = null,
    scope: CoroutineScope? = null,
    val oldEntryTime: OldEntryTime? = null,
    private val outbox: Lazy<Outbox>? = null,
) {
    val ack: AckClock = ack ?: AckClock()

    val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun policy(): SyncPolicy? = policies?.current

    /** Whether the backend settles each field by its stamp, as `/v4` does. */
    fun lastWriterWins(): Boolean = policy()?.lastWriterWins == true

    /**
     * Whether a membership edit that did not go, course sync being off, is kept until it is on:
     * under last writer wins (LWW D12, [SyncPolicy.keeps]). On `/v3` it is let go, as it always
     * was: the re-enable check answers for the courses once sync is back on.
     */
    fun keepsUnsent(): Boolean = policy()?.let { it.lastWriterWins && it.keeps(SyncCategory.COURSES) } == true

    /**
     * A course upload has landed under [signIn]: an edit that waited for the course goes again.
     * Never the upload's failure: what another channel does with the news is its own.
     */
    suspend fun courseUploadLanded(signIn: SignIn) {
        val outbox = outbox?.get() ?: return
        try {
            outbox.courseUploadLanded(signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a landed course upload's news failed: ${e.logSummary()}")
        }
    }

    private companion object {
        const val TAG = "MembershipOutbox"
    }
}
