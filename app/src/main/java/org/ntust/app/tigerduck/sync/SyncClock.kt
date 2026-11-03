package org.ntust.app.tigerduck.sync

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TigerSync's clock (`tigersync-clock`, last writer wins D1): the time a user edit is stamped with,
 * so the backend and both apps order the edits of one field alike. A stamp follows the device
 * clock, never falls below the last value the backend has shown this device or the last this
 * device issued, so two edits never share one, and is capped at the backend's time plus five
 * seconds as this device last heard it, so a device clock running ahead is not refused for it.
 *
 * Android ticks in whole milliseconds, as every queue already keeps its edit time in epoch ms: no
 * queue gains a field, and the wire value is a stamp times 1000, microseconds still.
 *
 * The wall clock is the system's, not the debug override's
 * ([org.ntust.app.tigerduck.shared.clock.AppClock]): a debug jump in time must not stamp edits.
 */
@Singleton
class SyncClock internal constructor(
    private val store: SyncClockStore,
    private val wallClock: () -> Long,
) {
    /**
     * The app's clock. [prefs] is unread: it named the API whose stamps were the raw wall clock,
     * which no base speaks since the move to `/v4`, and the app's wiring and its tests still pass it.
     */
    @Inject
    constructor(store: SyncClockStore, @Suppress("UNUSED_PARAMETER") prefs: AppPreferences) :
        this(store, System::currentTimeMillis)

    // A leaf: nothing done under it takes another of the app's locks, so a write the store makes at
    // once, under it, keeps the values in the order they were made and waits on nothing else.
    private val lock = Any()

    // Under lock: what the store holds, read before this process's first stamp or hearing, and
    // kept in step with it since.
    private var known: SyncClockState? = null

    /** The stamp of a user edit made now, in epoch ms. */
    fun stampMs(): Long = synchronized(lock) {
        val now = wallClock()
        val state = state()
        // Well past the wall clock, a stamp must outlive a process death: the next launch, its
        // wall clock still behind it, would stamp at or below it.
        state.stampMs(now).also { save(state.copy(lastIssuedMs = it), durable = it > now + SyncClockState.DURABLE_LEAD_MS) }
    }

    /** The backend's clock, from an `X-TigerSync-Clock` header heard now; ignored unless [isPlausibleClockUs]. */
    fun heard(serverClockUs: Long) {
        if (!isPlausibleClockUs(serverClockUs)) return
        synchronized(lock) {
            val now = wallClock()
            val state = state()
            val raised = serverClockUs > state.lastSeenUs
            val next = state.copy(lastSeenUs = maxOf(state.lastSeenUs, serverClockUs), heardUs = serverClockUs, heardAtMs = now)
            save(next, durable = raised && next.lastSeenUs > (now + SyncClockState.DURABLE_LEAD_MS) * 1_000)
        }
    }

    /**
     * A clock value read in a snapshot or in a write's answer: it raises the last seen value only,
     * and is ignored unless [isPlausibleClockUs].
     */
    fun heardInSnapshot(clockUs: Long) {
        if (!isPlausibleClockUs(clockUs)) return
        synchronized(lock) {
            val now = wallClock()
            val state = state()
            if (clockUs > state.lastSeenUs) {
                save(state.copy(lastSeenUs = clockUs), durable = clockUs > (now + SyncClockState.DURABLE_LEAD_MS) * 1_000)
            }
        }
    }

    /**
     * The new stamp of an edit the backend refused as ahead of its clock (409 `clock_ahead`), whose
     * answer carried [serverClockUs]: heard, the last issued value lowered to the cap, and stamped
     * again. Lowering it is safe: every stamp the backend stored is at most its time plus five
     * seconds, and the last seen value covers those this device has read (LWW D1).
     */
    fun restampAfterClockAhead(serverClockUs: Long?): Long = synchronized(lock) {
        if (serverClockUs != null) heard(serverClockUs)
        val state = state()
        val cap = state.capMs(wallClock())
        // Lost, a lowering only leaves the last issued value higher.
        if (cap != null && state.lastIssuedMs > cap) save(state.copy(lastIssuedMs = cap), durable = false)
        stampMs()
    }

    // Under lock.
    private fun state(): SyncClockState = known ?: store.load().also { known = it }

    // Under lock, so the store's writes go in the order the values were made. A value more than
    // [SyncClockState.DURABLE_LEAD_MS] ahead of the wall clock is [durable]: written before the
    // clock goes on.
    private fun save(state: SyncClockState, durable: Boolean) {
        known = state
        store.save(state, durable)
    }
}

/**
 * Whether [clockUs] can be a TigerSync clock value: a time from 2020 to 2100, in µs. Anything else
 * is a broken header or a broken file, and taken in, it would hold every later stamp past it for
 * good, as a stamp never goes below the last value seen, and a sign-out or a full reset keeps that.
 * The bound keeps a stamp's wire value, its ms times 1000, far from overflowing too.
 */
internal fun isPlausibleClockUs(clockUs: Long): Boolean = clockUs in EARLIEST_CLOCK_US..LATEST_CLOCK_US

// 2020-01-01T00:00:00Z and 2100-01-01T00:00:00Z, in µs.
internal const val EARLIEST_CLOCK_US = 1_577_836_800_000_000L
internal const val LATEST_CLOCK_US = 4_102_444_800_000_000L

/**
 * What the clock keeps: the last stamp it issued, in epoch ms; the last backend clock value it has
 * seen, in µs; and the last `X-TigerSync-Clock` heard, in µs, with the wall clock when it came, for
 * the cap. Nothing heard is no cap.
 */
internal data class SyncClockState(
    val lastIssuedMs: Long = 0,
    val lastSeenUs: Long = 0,
    val heardUs: Long? = null,
    val heardAtMs: Long = 0,
) {
    /** The backend's time at [nowMs] as last heard, plus the five seconds it allows; null with nothing heard. */
    fun capMs(nowMs: Long): Long? = heardUs?.let { it / 1_000 + maxOf(0, nowMs - heardAtMs) + SKEW_ALLOWED_MS }

    /** The last value seen, in the first whole ms at or past it. */
    val lastSeenMs: Long get() = -Math.floorDiv(-lastSeenUs, 1_000L)

    /** `max(min(now, cap), last seen, last issued)` plus one tick, `tigersync-clock`'s rule in ms. */
    fun stampMs(nowMs: Long): Long {
        val now = capMs(nowMs)?.let { minOf(nowMs, it) } ?: nowMs
        return maxOf(now, lastSeenMs, lastIssuedMs) + 1
    }

    internal companion object {
        // The backend refuses a stamp later than its time plus five seconds (LWW D5).
        const val SKEW_ALLOWED_MS = 5_000L

        // How far ahead of the wall clock a value must be to be written at once. A lost write
        // matters only if the process dies and starts again within that lead, and a restart takes
        // longer, so the wall clock alone passes a lost value nearer than this.
        const val DURABLE_LEAD_MS = 1_000L
    }
}
