// The course list's two outbox channels, the adds (PendingCourseAdds) and the deletes
// (PendingCourseDeletes): their entries, the stores their lanes keep them in, which are the files
// the queues kept before they were channels, and what the two read of the outbox and the policy.

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.QueueFile
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.model.editTimeOf
import org.ntust.app.tigerduck.data.model.withFloor
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.SyncPolicy
import org.ntust.app.tigerduck.sync.outbox.LaneStore
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime

/**
 * A class-table action's moment ([PendingCourseDeletes.stamp]): [at], its place in the order of
 * the course list's adds and deletes, the number their files keep it by, later than every earlier
 * action's whatever the clock does; and [madeAt], when it was made by the device's clocks, which
 * its age is read from. The two part once the clock was set back: [at] then runs ahead of the wall
 * clock, and [madeAt] never does.
 */
data class CourseMoment(val at: Long, val madeAt: EditTime) {
    /** [madeAt]'s clocks, to keep beside [at] ([EditClocks.of]). */
    internal val clocks: EditClocks? get() = EditClocks.of(madeAt, at)
}

/**
 * A course added by hand to [semester] whose upload waits, with the moment it was asked, if it has
 * one, and the device clocks at that moment and its floor ([clocks]), where kept.
 */
internal data class CourseAdd(val semester: String, val courseNo: String, val addedAtMs: Long?, val clocks: EditClocks? = null) {
    val field: String get() = membershipField(semester, courseNo)

    /** When the add was asked, by the device's clocks; null with no moment kept. */
    val addedAt: EditTime? get() = addedAtMs?.let { editTimeOf(it, clocks) }

    /** What its age counts from: [addedAt], or its floor where later ([EditClocks.floor]). */
    val agedFrom: EditTime? get() = addedAt?.atLeast(clocks?.floor?.time)

    /** This add counting its age from [floor] at least. */
    fun withFloor(floor: EditTime): CourseAdd = copy(clocks = clocks.withFloor(floor))

    /** Whether [other] is this add: its course and moment, whatever the clocks kept with it. */
    fun isSameEdit(other: CourseAdd): Boolean = semester == other.semester && courseNo == other.courseNo && addedAtMs == other.addedAtMs

    /**
     * Where the add's moment stood in the times file in the read that gave this add, the one
     * read of both files under the lane's lock ([CourseAddsStore.load]): the upload's timed adds
     * go in that order. Last when it has none or was not read so. Not part of the edit, so
     * neither compared nor copied.
     */
    var momentAt: Int = Int.MAX_VALUE
        private set

    /** This add, as read with its moment at [at] in the times file. */
    fun readWithMomentAt(at: Int?): CourseAdd = also { momentAt = at ?: Int.MAX_VALUE }
}

/**
 * A course deleted from [semester] whose DELETE waits, with the moment it was asked, and the device
 * clocks then and its floor ([clocks]), where kept.
 */
internal data class CourseDelete(val semester: String, val courseNo: String, val queuedAt: Long, val clocks: EditClocks? = null) {
    val field: String get() = membershipField(semester, courseNo)

    /** When the delete was asked, by the device's clocks. */
    val madeAt: EditTime get() = editTimeOf(queuedAt, clocks)

    /** What its age counts from: [madeAt], or its floor where later ([EditClocks.floor]). */
    val agedFrom: EditTime get() = madeAt.atLeast(clocks?.floor?.time)

    /** This delete counting its age from [floor] at least. */
    fun withFloor(floor: EditTime): CourseDelete = copy(clocks = clocks.withFloor(floor))

    /** Whether [other] is this delete: its course and moment, whatever the clocks kept with it. */
    fun isSameEdit(other: CourseDelete): Boolean = semester == other.semester && courseNo == other.courseNo && queuedAt == other.queuedAt
}

/** Where a course list's moment keeps its clocks in a clocks file ([MomentClocks]): by its course and the moment. */
internal fun momentKey(semester: String, courseNo: String, moment: Long): String = "$semester:$courseNo:$moment"

/** The field an add, a hide and a removal of [courseNo] in [semester] all edit. */
internal fun membershipField(semester: String, courseNo: String): String =
    SyncField.membership(SyncField.courseKey(semester, courseNo))

/** [this] by term, each term's in order, as the queue files hold them. */
private fun <E> List<E>.byTerm(semester: (E) -> String): Map<String, List<E>> = groupBy(semester)

/**
 * The adds' lane store: `pending_course_adds.json` (term to course numbers) and, beside it,
 * `pending_course_add_times.json` (term to course number to moment), as the queue kept them, so an
 * older install's file, whose adds have no moment, reads as it is, and the moments' device clocks
 * (`pending_course_add_clocks.json`, [MomentClocks]). The adds go in the files' order.
 */
internal class CourseAddsStore(
    private val dataCache: DataCache,
    private val clocks: MomentClocks = MomentClocks(dataCache, QueueFile.COURSE_ADD_CLOCKS),
) : LaneStore<CourseAdd> {

    override suspend fun load(): List<CourseAdd> {
        val times = dataCache.loadPendingCourseAddTimes()
        val clocksOf = clocks.load()
        return dataCache.loadPendingCourseAdds().flatMap { (semester, nos) ->
            val order = times[semester].orEmpty().keys.withIndex().associate { (at, courseNo) -> courseNo to at }
            nos.distinct().map { courseNo ->
                val at = times[semester]?.get(courseNo)
                CourseAdd(semester, courseNo, at, at?.let { clocksOf[momentKey(semester, courseNo, it)] }).readWithMomentAt(order[courseNo])
            }
        }
    }

    /**
     * A moment is written before its add and goes after it, so an add with a moment is never read
     * without it: sent as the oldest, it would lose to every hide and reset, an older one made
     * elsewhere included. Its clocks are written around both ([MomentClocks.saveAround]), as a
     * moment read without them ages by the wall clock. A file the change leaves as it is is not
     * written.
     */
    override suspend fun save(entries: List<CourseAdd>, signIn: SignIn): Boolean {
        val before = dataCache.loadPendingCourseAdds()
        val timesBefore = dataCache.loadPendingCourseAddTimes()
        val after = entries.byTerm { it.semester }.mapValues { (_, adds) -> adds.map { it.courseNo } }
        val timesAfter = momentsInPlace(entries, timesBefore)
        val clocksAfter = entries.mapNotNull { add -> add.addedAtMs?.let { at -> add.clocks?.let { momentKey(add.semester, add.courseNo, at) to it } } }.toMap()
        val added = after.any { (semester, nos) -> nos.any { it !in before[semester].orEmpty() } }
        suspend fun saveAdds() = after == before || dataCache.savePendingCourseAdds(after, signIn)
        suspend fun saveTimes() = timesAfter == timesBefore || dataCache.savePendingCourseAddTimes(timesAfter, signIn)
        return clocks.saveAround(clocksAfter, signIn) { if (added) saveTimes() && saveAdds() else saveAdds() && saveTimes() }
    }
}

/**
 * The device clocks of the course list's moments and their floors ([EditClocks]), by course and
 * moment ([momentKey]), in [file]: each add's or delete's, as its lane store writes it with the
 * entries ([saveAround]); by course too, as an older build's adds share their old-entry moment. A
 * file the entries leave as it is is not written. Open for a test that ends the process at one of
 * its writes.
 */
internal open class MomentClocks(private val dataCache: DataCache, private val file: QueueFile) {
    suspend fun load(): Map<String, EditClocks> = dataCache.loadEditClocks(file)

    open suspend fun save(clocks: Map<String, EditClocks>, signIn: SignIn): Boolean =
        clocks == load() || dataCache.saveEditClocks(file, clocks, signIn)

    /**
     * [clocks], every moment's that [write] writes, written around it: first beside the ones the
     * file holds, then, once [write] has written the moments, alone. A process death between any
     * two writes leaves each moment on disk, the old and the new, with its clocks.
     */
    suspend fun saveAround(clocks: Map<String, EditClocks>, signIn: SignIn, write: suspend () -> Boolean): Boolean =
        save(load() + clocks, signIn) && write() && save(clocks, signIn)
}

/**
 * [entries]' moments as the times file keeps them, where [before] has them: each where it was
 * first kept and a new one after the rest of its term's, a term's where it first had one, as the
 * queue has always written that file. The file's order is not the adds file's (an add asked first
 * with no moment and later with one is behind the others in it), and the upload lists its timed
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

/**
 * The deletes' lane store: `pending_course_deletes.json` (term to course number to moment), read
 * through [load], and the moments' device clocks (`pending_course_delete_clocks.json`), written
 * around the deletes ([MomentClocks.saveAround]), as a delete read without them ages by the wall clock.
 */
internal class CourseDeletesStore(
    private val dataCache: DataCache,
    private val load: suspend () -> Map<String, Map<String, Long>>,
    private val clocks: MomentClocks = MomentClocks(dataCache, QueueFile.COURSE_DELETE_CLOCKS),
) : LaneStore<CourseDelete> {

    override suspend fun load(): List<CourseDelete> {
        val clocksOf = clocks.load()
        return deletesOf(load.invoke(), clocksOf)
    }

    override suspend fun save(entries: List<CourseDelete>, signIn: SignIn): Boolean {
        val kept = entries.mapNotNull { delete -> delete.clocks?.let { momentKey(delete.semester, delete.courseNo, delete.queuedAt) to it } }.toMap()
        return clocks.saveAround(kept, signIn) { dataCache.savePendingCourseDeletes(deletesByTerm(entries), signIn) }
    }
}

/** The deletes [byTerm] holds (term to course number to moment), in its order, each with its moment's [clocks]. */
internal fun deletesOf(byTerm: Map<String, Map<String, Long>>, clocks: Map<String, EditClocks?> = emptyMap()): List<CourseDelete> =
    byTerm.flatMap { (semester, entries) -> entries.map { (courseNo, at) -> CourseDelete(semester, courseNo, at, clocks[momentKey(semester, courseNo, at)]) } }

/** The deletes [this] holds (term to course number to moment) but [courseNo]'s in [semester], when [asked] by its moment. */
internal fun Map<String, Map<String, Long>>.without(
    semester: String,
    courseNo: String,
    asked: (queuedAt: Long) -> Boolean,
): Map<String, Map<String, Long>> {
    val entries = this[semester].orEmpty()
    val queuedAt = entries[courseNo] ?: return this
    if (!asked(queuedAt)) return this
    val kept = entries - courseNo
    return if (kept.isEmpty()) this - semester else this + (semester to kept)
}

/** [deletes] as the file holds them: term to course number to moment, each term's in order. */
internal fun deletesByTerm(deletes: List<CourseDelete>): Map<String, Map<String, Long>> =
    deletes.byTerm { it.semester }.mapValues { (_, ofTerm) -> ofTerm.associate { it.courseNo to it.queuedAt } }

/**
 * What the course list's channels take from the outbox and the sync policy. Built with nothing,
 * as a test that builds a queue alone does, it reads no policy and keeps what did not go, its
 * acknowledgments are its own, and sends run on where their caller resumes them.
 *
 * - [policies] are read once per question.
 * - [ack] is the outbox's [AckClock], [scope] the application's, where a send runs to its end.
 * - [oldEntryTime] gives an add an older build kept with no moment its old-entry time (X1).
 *
 * A course upload that lands, the adds' as any other, is told to the outbox by the client that
 * sent it ([org.ntust.app.tigerduck.push.CourseUploadListener]).
 */
internal class MembershipOutbox(
    private val policies: SyncPolicies? = null,
    ack: AckClock? = null,
    scope: CoroutineScope? = null,
    val oldEntryTime: OldEntryTime? = null,
) {
    val ack: AckClock = ack ?: AckClock()

    val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun policy(): SyncPolicy? = policies?.current

    /**
     * Whether a membership edit that did not go, course sync being off, is kept until it is on
     * (LWW D12, [SyncPolicy.keeps]): on every build with TigerSync, and with no policy to read.
     */
    fun keepsUnsent(): Boolean = policy()?.keeps(SyncCategory.COURSES) ?: true
}
