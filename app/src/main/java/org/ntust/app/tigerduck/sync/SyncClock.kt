package org.ntust.app.tigerduck.sync

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
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
 * On `/v3` a stamp is the wall clock, as every `/v3` stamp has been, and nothing is recorded, so
 * `/v3` sends stay as they were. Hearing goes on at either version, so by the move to `/v4` the
 * clock knows the backend's time.
 *
 * The wall clock is the system's, not the debug override's
 * ([org.ntust.app.tigerduck.shared.clock.AppClock]): a debug jump in time must not stamp edits.
 */
@Singleton
class SyncClock internal constructor(
    private val store: SyncClockStore,
    /** The API the backend the endpoint setting names now speaks. */
    private val api: () -> BackendApi,
    private val wallClock: () -> Long,
) {
    @Inject
    constructor(store: SyncClockStore, prefs: AppPreferences) :
        this(store, { BackendApi.of(resolveAnnouncementEndpoint(prefs).url) }, System::currentTimeMillis)

    private val lock = Any()

    // Under lock: what the store holds, read before this process's first stamp or hearing, and
    // kept in step with it since.
    private var known: SyncClockState? = null

    /** The stamp of a user edit made now, in epoch ms. */
    fun stampMs(): Long = when (api()) {
        BackendApi.V3 -> wallClock()
        BackendApi.V4 -> synchronized(lock) {
            val state = state()
            state.stampMs(wallClock()).also { save(state.copy(lastIssuedMs = it)) }
        }
    }

    /** The backend's clock, from an `X-TigerSync-Clock` header heard now. */
    fun heard(serverClockUs: Long) {
        synchronized(lock) {
            val state = state()
            save(state.copy(lastSeenUs = maxOf(state.lastSeenUs, serverClockUs), heardUs = serverClockUs, heardAtMs = wallClock()))
        }
    }

    /** A clock value read in a snapshot or in a write's answer: it raises the last seen value only. */
    fun heardInSnapshot(clockUs: Long) {
        synchronized(lock) {
            val state = state()
            if (clockUs > state.lastSeenUs) save(state.copy(lastSeenUs = clockUs))
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
        if (cap != null && state.lastIssuedMs > cap) save(state.copy(lastIssuedMs = cap))
        stampMs()
    }

    // Under lock.
    private fun state(): SyncClockState = known ?: store.load().also { known = it }

    // Under lock, so the store's writes go in the order the values were made.
    private fun save(state: SyncClockState) {
        known = state
        store.save(state)
    }
}

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

    /** `max(min(now, cap), last seen, last issued)` plus one tick, `tigersync-clock`'s rule in ms. */
    fun stampMs(nowMs: Long): Long {
        val now = capMs(nowMs)?.let { minOf(nowMs, it) } ?: nowMs
        return maxOf(now, -Math.floorDiv(-lastSeenUs, 1_000L), lastIssuedMs) + 1
    }

    private companion object {
        // The backend refuses a stamp later than its time plus five seconds (LWW D5).
        const val SKEW_ALLOWED_MS = 5_000L
    }
}
