package org.ntust.app.tigerduck.sync

import okhttp3.HttpUrl
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.ApiBase
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
 * The wall clock is the system's, not the debug override's
 * ([org.ntust.app.tigerduck.shared.clock.AppClock]): a debug jump in time must not stamp edits.
 *
 * What it keeps is one backend's: the values heard from it and the stamps issued under it. A move
 * of the endpoint to another backend ([endpoint], [ApiBase.backendKey]) forgets them, as another
 * backend's clock says nothing of this one's, and one that ran far ahead would hold every stamp up
 * on the next. A sign-out keeps them, as the clock spec says, and so does a move between the API
 * versions of one backend. For the same reason a value comes only from the backend the endpoint
 * names as it is heard: one whose response, given as `from`, went to another, the endpoint having
 * moved while it was out, is not heard, and a refusal of its is no restamp (Codex on PR P).
 */
@Singleton
class SyncClock internal constructor(
    private val store: SyncClockStore,
    private val wallClock: () -> Long,
    // The base the endpoint names now; null when it cannot be read, which forgets and turns away nothing.
    private val endpoint: () -> String? = { null },
) {
    /** The app's clock, for the backend the endpoint setting in [prefs] names. */
    @Inject
    constructor(store: SyncClockStore, prefs: AppPreferences) :
        this(store, System::currentTimeMillis, { runCatching { resolveAnnouncementEndpoint(prefs).url }.getOrNull() })

    // A leaf: nothing done under it takes another of the app's locks, so a write the store makes at
    // once, under it, keeps the values in the order they were made and waits on nothing else.
    private val lock = Any()

    // Under lock: what the store holds, read before this process's first stamp or hearing, and
    // kept in step with it since.
    private var known: SyncClockState? = null

    // Each step below reads the endpoint once, under the lock, and keeps to that one backend for its
    // check, its state and its write: the setting changes without the lock, and a move between two
    // reads would put one backend's values into the other's (Codex on PR P, round 2).

    /** The stamp of a user edit made now, in epoch ms. */
    fun stampMs(): Long = synchronized(lock) { stamp(endpoint()) }

    // Under lock: [stampMs] for the backend [base] names.
    private fun stamp(base: String?): Long {
        val now = wallClock()
        val state = state(base)
        // Well past the wall clock, a stamp must outlive a process death: the next launch, its
        // wall clock still behind it, would stamp at or below it.
        return state.stampMs(now).also { save(state.copy(lastIssuedMs = it), durable = it > now + SyncClockState.DURABLE_LEAD_MS) }
    }

    /**
     * The backend's clock, from an `X-TigerSync-Clock` header heard now, on a response to [from];
     * ignored unless [isPlausibleClockUs], and unless [from] went to the backend the endpoint names.
     */
    fun heard(serverClockUs: Long, from: HttpUrl? = null) {
        if (!isPlausibleClockUs(serverClockUs)) return
        synchronized(lock) {
            val base = endpoint()
            if (servedBy(base, from)) hear(serverClockUs, base)
        }
    }

    // Under lock: [heard] for the backend [base] names.
    private fun hear(serverClockUs: Long, base: String?) {
        val now = wallClock()
        val state = state(base)
        val raised = serverClockUs > state.lastSeenUs
        val next = state.copy(lastSeenUs = maxOf(state.lastSeenUs, serverClockUs), heardUs = serverClockUs, heardAtMs = now)
        save(next, durable = raised && next.lastSeenUs > (now + SyncClockState.DURABLE_LEAD_MS) * 1_000)
    }

    /**
     * A clock value read in a snapshot or in a write's answer, to [from]: it raises the last seen
     * value only, and is ignored unless [isPlausibleClockUs], and unless [from] went to the backend
     * the endpoint names.
     */
    fun heardInSnapshot(clockUs: Long, from: HttpUrl? = null) {
        if (!isPlausibleClockUs(clockUs)) return
        synchronized(lock) {
            val base = endpoint()
            if (!servedBy(base, from)) return
            val now = wallClock()
            val state = state(base)
            if (clockUs > state.lastSeenUs) {
                save(state.copy(lastSeenUs = clockUs), durable = clockUs > (now + SyncClockState.DURABLE_LEAD_MS) * 1_000)
            }
        }
    }

    /**
     * The new stamp of an edit the backend refused as ahead of its clock (409 `clock_ahead`), whose
     * answer carried [serverClockUs]: heard, and stamped one tick past it, so strictly after the
     * refusal's clock and at most a millisecond past (`tigersync-clock`, "A device re-stamps an edit
     * refused as ahead"; iOS resends one microsecond past it). The last value issued is never lowered
     * for it, and each restamp follows the one before, so a still wall clock never issues one value
     * twice (Telegram 1638, 1651). An answer with no plausible clock is stamped as an edit made now.
     *
     * Nor does it fall below the last value seen: the refusal's clock is the backend's wall clock,
     * which took an earlier edit stamped up to five seconds past it, and every write's answer gives
     * back the stamp the backend kept, which this clock hears ([heardInSnapshot]). A restamp at or
     * below that stamp would lose to it (last writer wins: ties and older lose), and the student's
     * newer value would revert (Codex on PR P); so would one below another device's edit this phone
     * has seen. A value seen was at most the backend's time plus five seconds when it was stored, so
     * the restamp stays within the refusal's clock plus five seconds. A floor past that, which the
     * backend could take no stamp above now, is no stamp it stored: a clock it reported ahead once
     * and corrected since, which would keep every restamp refused. It counts for nothing (Codex on
     * PR P, round 2). One at the edge itself, the refusal's clock plus five seconds to the ms, may be
     * a stamp stored as the refusal came: it still counts, the restamp a millisecond past it is
     * refused once, and the channel sends it at the next sync, when the backend's clock has moved on,
     * rather than send one at or below that stamp, which would be taken and lose.
     *
     * Null, with nothing heard or changed, when [refused], the refused request, went to a backend
     * the endpoint has moved off since: its clock is that backend's, and its write goes no further.
     */
    fun restampAfterClockAhead(serverClockUs: Long?, refused: HttpUrl): Long? = synchronized(lock) {
        val base = endpoint()
        if (servedBy(base, refused)) restamp(serverClockUs, base) else null
    }

    /** [restampAfterClockAhead] for a refusal whose request is not named: from the backend the endpoint names. */
    fun restampAfterClockAhead(serverClockUs: Long?): Long = synchronized(lock) { restamp(serverClockUs, endpoint()) }

    // Under lock: the restamp, for the backend [base] names, which the refused request went to.
    private fun restamp(serverClockUs: Long?, base: String?): Long {
        val refusedAt = serverClockUs?.takeIf(::isPlausibleClockUs) ?: return stamp(base)
        hear(refusedAt, base)
        // In whole ms, strictly past each value in µs: the refusal's clock, and each floor the backend
        // could have stored, at most its clock plus five seconds: the last restamp, the last value seen.
        val refusedMs = Math.floorDiv(refusedAt, 1_000L)
        val state = state(base)
        val floors = listOf(lastRestampMs, Math.floorDiv(state.lastSeenUs, 1_000L)).filter { it <= refusedMs + SyncClockState.SKEW_ALLOWED_MS }
        val stamp = maxOf(refusedMs, floors.maxOrNull() ?: 0L) + 1
        lastRestampMs = stamp
        // Below the last value issued, as it is for an edit stamped ahead, it leaves that alone.
        if (stamp > state.lastIssuedMs) save(state.copy(lastIssuedMs = stamp), durable = stamp > wallClock() + SyncClockState.DURABLE_LEAD_MS)
        return stamp
    }

    // Under lock: the last restamp issued in this process, which the next stays above. A process
    // death needs none: the backend's clock has moved past it by the next launch.
    private var lastRestampMs = 0L

    // Whether [from] went to the backend [base] names; any, with no base or no request named.
    private fun servedBy(base: String?, from: HttpUrl?): Boolean = from == null || base == null || ApiBase.serves(base, from)

    // Under lock: as the store holds it, for the backend [base], the endpoint now, names. Values kept
    // for another are forgotten; those a build before the key kept are taken as this one's.
    private fun state(base: String?): SyncClockState {
        val held = known ?: store.load().also { known = it }
        val key = base?.let(ApiBase::backendKey) ?: return held
        if (held.backendKey == key) return held
        val next = if (held.backendKey == null) held.copy(backendKey = key) else SyncClockState(backendKey = key).also { lastRestampMs = 0 }
        save(next, durable = true)
        return next
    }

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
    // The backend they are of (ApiBase.backendKey); null for none named yet, as a build before it kept them.
    val backendKey: String? = null,
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
