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
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.sync.outbox.OutboxLane
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
 * and at a process's first [drain] (spec §5.3 race 3), unless its course's
 * upload left it out ([courseUploadAnswered]). Account-scoped: sent only
 * under the sign-in that made it, and wiped with the user data at sign-out.
 *
 * A change made while TigerSync or the kind's switch is off is kept, and
 * goes once both are on (LWW D12), and so is every one while the kind's
 * re-enable check waits for the user ([sends] false): the answer decides them
 * ([records] is `SyncPolicy.keeps`, [sends] `SyncPolicy.sends`). On a build
 * without TigerSync ([records] false) colors or names are this phone's own:
 * a change goes nowhere and is not kept, but replaces one waiting for the
 * same key, as a mark does in [UnsentMarks]. Use Server drops them
 * ([adoptServer]); Use Local queues its own and sends them once the hold is
 * lifted. A pull keeps every change still waiting, and every one the backend
 * acknowledged since its snapshot began (LWW D12, [heldFields]).
 *
 * Each change keeps its time by the device's clocks and goes with its age (`age_ms`), by its course's key
 * (`client:{semester}:{course_no}`), from its own term and number or, for a row an older build
 * wrote without, its Moodle id ([legacyPlaceOf]); one with no time with its old-entry time (X1),
 * and a reply that the backend kept a later value puts it here unless a newer change waits.
 */
@Singleton
class PendingCourseOverrides internal constructor(
    private val dataCache: DataCache,
    writer: CourseOverrideWriter,
    private val records: (color: Boolean) -> Boolean,
    private val sends: (color: Boolean) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    // The clocks a change's time is read by: [clock] alone, with no elapsed clock, for a test's queue.
    private val clocks: DeviceClocks = DeviceClocks.wallOnly(clock),
    ack: AckClock = AckClock(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val oldEntryTime: OldEntryTime? = null,
    internal val catalogue: () -> List<String> = { emptyList() },
) : OutboxChannel {

    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        policies: SyncPolicies = SyncPolicies(prefs),
        /** What a change's time is read by; the wall clock alone in tests. */
        clocks: DeviceClocks = DeviceClocks.WALL,
        ack: AckClock = AckClock(),
        @ApplicationScope scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        /** The old-entry time of a change an older build wrote with no time; null only in tests, for none. */
        oldEntryTime: OldEntryTime? = null,
        semesterCatalog: org.ntust.app.tigerduck.network.SemesterCatalog? = null,
    ) : this(
        dataCache = dataCache,
        writer = CourseOverrideWriter { change, target, signIn, allowed, madeAt ->
            pushApiClient.patchCourseOverrideAnswered(target, signIn, change.colorHex, change.customName, change.locale, allowed, madeAt)
        },
        records = { color -> policies.current.keeps(kindOf(color)) },
        sends = { color -> policies.current.sends(kindOf(color)) },
        clocks = clocks,
        ack = ack,
        scope = scope,
        oldEntryTime = oldEntryTime,
        catalogue = { semesterCatalog?.availableSemesters().orEmpty() },
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

    // A change that landed waits for one being recorded under [mutex] before the lane hands over the
    // next, which it then reads after that change is kept: a batch's change that a newer one is
    // replacing, as a reset's color a pick made while the reset goes up, does not go up.
    private val sendPath = CourseOverrideSends(
        dataCache, writer, sends, catalogue,
        waiting = { lane.entries() },
        landed = { mutex.withLock { } },
    )

    // One PATCH at a time, so changes reach the backend in the order they were
    // made: the backend keeps whichever request lands last.
    private val lane: OutboxLane<PendingCourseOverride> = OutboxLane(
        OutboxChannelId.COURSE_OVERRIDES,
        CourseOverridesStore(dataCache),
        courseOverridesPolicy(MAX_ATTEMPTS) {
            oldEntryTime?.oldEntryTimeMs(dataCache.queueFile(QueueFile.COURSE_OVERRIDES)) ?: 0L
        },
        ack,
        scope,
        sendPath::send,
    )

    /** Whether a colour or name set here has yet to reach the backend: data a sign-out would lose. */
    suspend fun hasPending(): Boolean = waiting().isNotEmpty()

    /**
     * Keeps one change (see [Change] for the arguments), in place of any older change of the same,
     * and sends it unless its kind's re-enable check is pending; [kept] hears once it is kept, before
     * it is sent. Called in the order the changes were made; nothing to do without a colour or a name.
     */
    suspend fun send(
        moodleId: String,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
        semester: String? = null,
        courseNo: String? = null,
        signIn: SignIn,
        kept: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) {
        // Under way from before it is kept, so a drain already running that sends it does not leave it to send again.
        lane.keepAndDrain(signIn, { sends(colorHex != null) }) {
            record(listOf(Change(moodleId, colorHex, customName, locale, semester, courseNo)), signIn).values.mapTo(HashSet(), ::fieldOf)
                .also { kept?.complete(Unit) }
        }
    }

    /**
     * Keeps [changes], each in place of an older change of the same, all at
     * [version] (from [reserveVersion], taken before the data they were read
     * from), and sends none: [drain] does, once the caller has saved what
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
     * Keeps [change] at the oldest time ([EditTime.OLDEST]) and version unless a change of its key waits (LWW D13: it fills
     * only a field the backend has no stamp for). Sends nothing; a kind's first sync queues it.
     */
    suspend fun queueAsOldest(change: Change, signIn: SignIn): Boolean = mutex.withLock {
        val row = PendingCourseOverride(
            change.moodleId, change.colorHex, change.customName, change.locale, clock(), change.semester, change.courseNo,
            seq = 0L, madeAtMs = EditTime.OLDEST.wallMs,
        )
        val key = row.key ?: return@withLock false
        lane.edit(signIn) { rows -> if (rows.any { it.key == key }) rows else rows + row }
    }

    /**
     * Sends every change still waiting whose kind goes out, a failed one waiting for the next sync;
     * each full sync starts with this. The first under a sign-in in a process gives each parked
     * change one more try, as its upload may have landed in a process before.
     */
    override suspend fun drain(signIn: SignIn) {
        if (memory.firstParkedTry(signIn)) requeue(signIn, inFlightToo = false)
        lane.drain(signIn)
    }

    /**
     * A course upload has landed: each parked change gets one more try at the next [drain], and so
     * does the one whose PATCH is out, which the upload may have made the row for (the lane's rule),
     * but none of a course the last upload left out as tombstoned ([courseUploadAnswered]).
     */
    override suspend fun requeueParked(signIn: SignIn) = requeue(signIn, inFlightToo = true)

    // The lane's requeue, but of no change of a skipped course; the skips are read under [mutex].
    private suspend fun requeue(signIn: SignIn, inFlightToo: Boolean) = mutex.withLock { lane.requeueParked(signIn, inFlightToo) { !memory.skips(it) } }

    /**
     * A course upload of [semester] was answered: the backend took the courses [accepted] and left
     * [skipped] out as tombstoned, as it does a course hidden or reset since elsewhere. No upload
     * makes a row for one, so a change parked for its row goes, now or at the next answer, which
     * the course adds' every upload gives (xinshou, Telegram 1663); a course taken since is no
     * longer skipped. The check and the drop are one step under the queue's lock.
     */
    suspend fun courseUploadAnswered(semester: String, accepted: Collection<String>, skipped: Collection<String>, signIn: SignIn) {
        mutex.withLock {
            memory.uploadAnswered(semester, accepted, skipped, signIn)
            lane.edit(signIn) { rows -> rows.filterNot { it.isParked && memory.skips(it) } }
        }
    }

    override suspend fun heldFields(): Set<String> = lane.heldFields()

    /**
     * Each change of [key]'s term made at or after [start] ages from [end] at least: a row an older
     * build kept by Moodle id alone by the term its send goes to ([CourseOverrideSends.placeFor]).
     */
    override suspend fun raiseFloors(key: String, start: EditTime, end: EditTime, signIn: SignIn) {
        lane.raiseFloors(key, start, end, signIn, termOf())
    }

    override suspend fun floorOf(key: String, madeAt: EditTime): EditTime? = lane.floorOf(key, madeAt, termOf())

    // Each change's term's key: by its term, or, for a row an older build kept by Moodle id alone, where its send goes.
    private suspend fun termOf(): (PendingCourseOverride) -> String? {
        val legacy = lane.entries().filter { placeOf(it) == null }.associate { it.moodleId to sendPath.placeFor(it)?.semester }
        return { row -> (placeOf(row)?.semester ?: legacy[row.moodleId])?.let(SyncField::termReset) }
    }

    /** At a sign-out: the next student's parked changes get their own one more try in this process. */
    override fun forgetMemory() = lane.forgetMemory().also { memory.forgetParkedTry() }

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
            lane.edit(signIn) { rows -> rows.filter { category(it.isColor) !in categories } }
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
            val madeAt = clocks.now()
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
                        c.moodleId, c.colorHex, c.customName, c.locale, now, place?.semester, place?.courseNo, seq = seq,
                        madeAtMs = madeAt.wallMs, madeAtClocks = EditClocks.of(madeAt),
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

        // The sync category colours ([color]) or names are.
        private fun kindOf(color: Boolean): SyncCategory = if (color) SyncCategory.COURSE_COLORS else SyncCategory.COURSE_NAMES

        /** The re-enable conflict category colours ([color]) or names belong to. */
        fun category(color: Boolean): String = kindOf(color).stored
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
