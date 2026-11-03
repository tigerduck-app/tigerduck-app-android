package org.ntust.app.tigerduck.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.cache.QueueFile
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Colours and names set on the class table that the backend has not
 * confirmed, by course and, for a name, locale.
 *
 * A change went up once. Failed, as it does offline, it was only logged: the
 * colour or name stayed on this phone alone, never on another device, and
 * lost at sign-out (tigerduck-app-android#380). Now the change waits on disk,
 * kept before the request so a process killed during it keeps it too, and
 * every full sync sends what waits first, as [PendingCourseAdds] does for
 * hand-added courses. Every colour and name PATCH goes through here — a
 * reset of every colour, a sync's moved colours, Use Local's — so a later
 * change of the same colour, or of the same name, replaces the one waiting
 * and the older never lands over it. Each change carries a version that only
 * grows ([PendingCourseOverride.seq]); a batch ([queue]) is written whole at
 * a version reserved before its data was read, so a change made while the
 * batch is read or goes up one by one is the newer, landed already or not,
 * and is not replaced when the batch's own reaches its turn. Each records
 * its course's term and number, from the current term's courses when its
 * caller names none. The changes wait in an [OutboxLane] over the queue's own
 * file, one per colour or name ([fieldOf]), one PATCH at a time
 * ([CourseOverrideSends]). A change goes once the backend confirms it, or at
 * once on a 404 for a course no longer cached in the term it was made in: the
 * row went with the course. Refused [MAX_ATTEMPTS] times, or answered that
 * the backend has no such course yet, it parks, never let go: kept at this
 * phone's value, and tried again once a course upload lands ([requeueParked])
 * and at a process's first [retry] (spec §5.3 race 3). Account-scoped: sent
 * only under the sign-in that made it, and wiped with the user data at sign-out.
 *
 * With TigerSync or the kind's switch off ([records] false) colours or names
 * are this phone's own: a change goes nowhere and is not kept, but replaces
 * one waiting for the same key, as a mark does in [UnsentMarks]. One already
 * waiting when the switch goes off is kept and not sent, and so is every one
 * while the kind's re-enable check waits for the user ([sends] false): the
 * answer decides them ([records] is `SyncPolicy.keeps`, [sends]
 * `SyncPolicy.sends`). Use Server drops them ([adoptServer]); Use Local
 * queues its own and sends them once the hold is lifted. A snapshot fetched
 * before a change, or before its PATCH landed, still has the old value;
 * [held] names what a sync must not take from it.
 *
 * Each change is stamped by the sync clock, which on `/v3` is the wall clock
 * as it always was. On `/v4` a change goes by its course's key
 * (`client:{semester}:{course_no}`), from its own term and number or, for a
 * row an older build wrote without them, the current term's course with its
 * Moodle id; one made while its kind does not sync is kept and goes once it
 * does (LWW D12); one an older build wrote with no time goes with its
 * old-entry time (X1); and a reply that says the backend kept a later value
 * puts it on this phone unless a newer change waits.
 */
@Singleton
class PendingCourseOverrides internal constructor(
    private val dataCache: DataCache,
    writer: CourseOverrideWriter,
    private val records: (color: Boolean) -> Boolean,
    private val sends: (color: Boolean) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    // The time a change is stamped with: the sync clock's, which is the wall clock on /v3.
    private val stamp: () -> Long = clock,
    // Whether the backend settles each field by its stamp, as /v4 does.
    private val lastWriterWins: () -> Boolean = { false },
    ack: AckClock = AckClock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val oldEntryTime: OldEntryTime? = null,
) : OutboxChannel {

    /** The queue for a test that sends through a [CourseOverrideSender], as the queue's own tests do. */
    internal constructor(
        dataCache: DataCache,
        sender: CourseOverrideSender,
        records: (color: Boolean) -> Boolean,
        sends: (color: Boolean) -> Boolean,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(dataCache, sender.answering(), records, sends, clock)

    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        policies: SyncPolicies = SyncPolicies(prefs),
        /** What a change is stamped by; null only in tests, for the wall clock. */
        syncClock: SyncClock? = null,
        ack: AckClock = AckClock(),
        @ApplicationScope scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        /** The old-entry time of a change an older build wrote with no time; null only in tests, for none. */
        oldEntryTime: OldEntryTime? = null,
    ) : this(
        dataCache = dataCache,
        writer = CourseOverrideWriter { change, target, signIn, allowed ->
            pushApiClient.patchCourseOverrideAnswered(target, signIn, change.colorHex, change.customName, change.locale, allowed, change.madeAtMs)
        },
        records = { color -> policies.current.keeps(kindOf(color)) },
        sends = { color -> policies.current.sends(kindOf(color)) },
        stamp = syncClock?.let { it::stampMs } ?: System::currentTimeMillis,
        lastWriterWins = { policies.current.lastWriterWins },
        ack = ack,
        scope = scope,
        oldEntryTime = oldEntryTime,
    )

    override val id: OutboxChannelId get() = OutboxChannelId.COURSE_OVERRIDES

    /**
     * One change for [queue]: the colour [colorHex], or the name [customName]
     * in [locale], of the course the backend knows as [moodleId]. [semester]
     * and [courseNo] say where this phone holds the course, when known; see
     * [PendingCourseOverride.semester].
     */
    data class Change(
        val moodleId: String,
        val colorHex: String? = null,
        val customName: String? = null,
        val locale: String? = null,
        val semester: String? = null,
        val courseNo: String? = null,
    )

    private val mutex = Mutex()

    // The versions given out and the keys' last changes, under [mutex].
    private val memory = CourseOverrideMemory(clock)

    // Whether this process has given the parked changes their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    private val sendPath = CourseOverrideSends(
        dataCache, writer, sends, lastWriterWins,
        waiting = { lane.entries() },
        confirmed = { key, signIn -> mutex.withLock { memory.touched(listOf(key), clock(), signIn) } },
    )

    // One PATCH at a time, so changes reach the backend in the order they were
    // made: the backend keeps whichever request lands last.
    private val lane: OutboxLane<PendingCourseOverride> = OutboxLane(
        OutboxChannelId.COURSE_OVERRIDES,
        CourseOverridesStore(dataCache),
        courseOverridesPolicy(MAX_ATTEMPTS, lastWriterWins) {
            oldEntryTime?.oldEntryTimeMs(dataCache.queueFile(QueueFile.COURSE_OVERRIDES)) ?: 0L
        },
        ack,
        scope,
        sendPath::send,
    )

    /** Whether a colour or name set here has yet to reach the backend: data a sign-out would lose. */
    suspend fun hasPending(): Boolean = waiting().isNotEmpty()

    /**
     * Keeps one change (see [Change] for the arguments), in place of any
     * older change of the same, and sends it unless its kind's re-enable
     * check is pending. Called in the order the changes were made; nothing
     * to do without a colour or a name.
     */
    suspend fun send(
        moodleId: String,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
        semester: String? = null,
        courseNo: String? = null,
        signIn: SignIn,
    ) {
        val recorded = record(listOf(Change(moodleId, colorHex, customName, locale, semester, courseNo)), signIn)
        for (change in recorded.values) {
            if (sends(change.isColor)) lane.drain(signIn, setOf(fieldOf(change)))
        }
    }

    /**
     * Keeps [changes], each in place of an older change of the same, all at
     * [version] (from [reserveVersion], taken before the data they were read
     * from), and sends none: [retry] does, once the caller has saved what
     * they stand for. A reset of every colour goes through here before it
     * saves the colours, so a pick made while they are read or go up one by
     * one is the newer, and the reset's own colour for that course, reaching
     * its turn, neither replaces it nor goes up.
     */
    suspend fun queue(changes: List<Change>, version: Long, signIn: SignIn) {
        if (changes.isEmpty()) return
        record(changes, signIn, version)
    }

    /**
     * A version for a batch whose changes are yet to be read, taken before
     * the read: an edit recorded while the read completes gets a later one,
     * and [queue]'s batch, built from data that predates the edit, neither
     * replaces it nor goes up over it. In memory only: a reservation lives in
     * its caller and does not outlive the process.
     */
    suspend fun reserveVersion(): Long = mutex.withLock { memory.nextSeq(lane.entries()) }

    /**
     * Keeps [change] stamped 0 and at the oldest version, unless a change of its colour or name
     * waits: a value this phone held before the kind ever synced, which fills only a field the
     * backend holds no stamp for (LWW D13). Sends nothing; nothing calls it until a kind's first
     * sync on `/v4` does. False once [signIn] has ended.
     */
    suspend fun queueAsOldest(change: Change, signIn: SignIn): Boolean = mutex.withLock {
        val row = PendingCourseOverride(
            change.moodleId, change.colorHex, change.customName, change.locale, clock(), change.semester, change.courseNo,
            seq = 0L, madeAtMs = OLDEST,
        )
        val key = row.key ?: return@withLock false
        lane.edit(signIn) { rows -> if (rows.any { it.key == key }) rows else rows + row }
    }

    /**
     * Sends every change still waiting whose kind goes out, a failed one waiting for the next sync;
     * each full sync starts with this, so the snapshot it fetches has them. The first under a
     * sign-in in a process gives each parked change one more try: the upload it waits for may
     * have landed in a process before, or by a path that does not tell the outbox.
     */
    suspend fun retry(signIn: SignIn) = drain(signIn)

    /** [retry]. */
    override suspend fun drain(signIn: SignIn) {
        if (signIn.ifCurrent { parkedRequeued.compareAndSet(false, true) } == true) lane.requeueParked(signIn)
        lane.drain(signIn)
    }

    /** A course upload has landed: each parked change gets one more try at the next [retry]. */
    override suspend fun requeueParked(signIn: SignIn) = lane.requeueParked(signIn)

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    /** At a sign-out: the next student's parked changes get their own one more try in this process. */
    override fun forgetMemory() = lane.forgetMemory().also { parkedRequeued.set(false) }

    /**
     * The keys ([PendingCourseOverride.key]) a snapshot fetched at
     * [fetchedAtMs] must not overwrite: every change still waiting, and
     * every one made or confirmed since the fetch, which the snapshot
     * predates.
     */
    suspend fun held(fetchedAtMs: Long): Set<String> = mutex.withLock {
        waiting().keys + memory.touchedSince(fetchedAtMs)
    }

    /**
     * Forgets what waits for the courses [moodleIds]: deleted by hand or on
     * another device, or their term reset, and the backend's override went
     * with the course row. Sent later, a change would set its colour or name
     * on the row a re-add or an upload makes anew, and until then be refused
     * at every sync. Only the changes older than [madeBefore], a version
     * [reserveVersion] gave before the course went: one made since is the
     * course's put back meanwhile, and stays.
     */
    suspend fun forget(moodleIds: Collection<String>, madeBefore: Long = Long.MAX_VALUE, signIn: SignIn) {
        if (moodleIds.isEmpty()) return
        mutex.withLock {
            lane.edit(signIn) { rows -> rows.filter { it.moodleId !in moodleIds || it.seq >= madeBefore } }
        }
    }

    /**
     * Drops the changes of the kinds in [categories] (`course_colors`,
     * `course_names`; others are ignored), the user having taken the
     * server's over this phone's at their re-enable check: sent later, they
     * would put this phone's back.
     */
    suspend fun adoptServer(categories: Collection<String>, signIn: SignIn) {
        mutex.withLock {
            var dropped = emptySet<String>()
            val written = lane.edit(signIn) { rows ->
                rows.filter { category(it.isColor) !in categories }.also { kept -> dropped = (rows - kept.toSet()).mapNotNullTo(HashSet()) { it.key } }
            }
            if (written) memory.untouched(dropped, signIn)
        }
    }

    /**
     * Writes [changes] at [version], or at a new one, each in place of the
     * change waiting under its key unless a newer one was recorded under it,
     * waiting still or landed since: made after the version was reserved,
     * from data the batch predates, so it stands. A change of a kind that
     * does not sync is this phone's own: not kept, and the last word on its
     * key, as one still waiting would go up over it later. Returns the ones
     * now waiting, by key; nothing is written once [signIn] has ended.
     */
    private suspend fun record(
        changes: List<Change>,
        signIn: SignIn,
        version: Long? = null,
    ): Map<String, PendingCourseOverride> =
        mutex.withLock {
            // Where a change names no term, the current term's course with its Moodle id.
            val places = if (changes.all { it.semester != null && it.courseNo != null }) emptyMap() else currentTermPlaces(dataCache)
            val now = clock()
            val madeAtMs = stamp()
            var seq = 0L
            var recorded = LinkedHashMap<String, PendingCourseOverride>()
            // In one step with the lane's own writes; the versions are this queue's, under [mutex].
            val written = lane.edit(signIn) { rows ->
                val waiting = rows.associateByTo(LinkedHashMap()) { it.key.orEmpty() }
                seq = version ?: memory.nextSeq(waiting.values)
                val updated = LinkedHashMap(waiting)
                recorded = LinkedHashMap()
                for (c in changes) {
                    val place = if (c.semester != null && c.courseNo != null) CoursePlace(c.semester, c.courseNo) else places[c.moodleId]
                    val entry = PendingCourseOverride(
                        c.moodleId, c.colorHex, c.customName, c.locale, now, place?.semester, place?.courseNo, seq = seq, madeAtMs = madeAtMs,
                    )
                    val key = entry.key ?: continue
                    if (memory.outbids(key, waiting[key]?.seq, seq)) continue
                    if (!records(entry.isColor)) {
                        updated.remove(key)
                        memory.recordedAt(key, seq)
                        continue
                    }
                    updated[key] = entry
                    recorded[key] = entry
                }
                if (updated == waiting) recorded = LinkedHashMap()
                updated.values.toList()
            }
            if (!written || recorded.isEmpty()) return@withLock emptyMap()
            memory.touched(recorded.keys, now, signIn)
            for (key in recorded.keys) memory.recordedAt(key, seq)
            recorded
        }

    // By key, in the order the changes were made.
    private suspend fun waiting(): Map<String, PendingCourseOverride> =
        lane.entries().associateByTo(LinkedHashMap()) { it.key.orEmpty() }

    private val PendingCourseOverride.isColor: Boolean
        get() = colorHex != null

    companion object {
        /** Refusals before a change is parked, as the marks and course ignores park. */
        const val MAX_ATTEMPTS = 5

        // The stamp of a value queued as the oldest there is (LWW D13).
        private const val OLDEST = 0L

        // The sync category colours ([color]) or names are.
        private fun kindOf(color: Boolean): SyncCategory = if (color) SyncCategory.COURSE_COLORS else SyncCategory.COURSE_NAMES

        /** The re-enable conflict category colours ([color]) or names belong to. */
        fun category(color: Boolean): String = kindOf(color).stored

        /** Whether a colour ([color]) or name is kept to send: `SyncPolicy.keeps` of its kind, as the switches stand. */
        fun records(prefs: AppPreferences, color: Boolean): Boolean = SyncPolicies(prefs).current.keeps(kindOf(color))

        /** Whether it goes out: `SyncPolicy.sends` of its kind, which its re-enable check holds. */
        fun sends(prefs: AppPreferences, color: Boolean): Boolean = SyncPolicies(prefs).current.sends(kindOf(color))
    }
}

/** The course colors and names join the outbox's channels (`OutboxModule`). */
@Module
@InstallIn(SingletonComponent::class)
object CourseOverridesChannelModule {
    @Provides
    @IntoSet
    fun channel(overrides: PendingCourseOverrides): OutboxChannel = overrides
}
