package org.ntust.app.tigerduck.sync

import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInBound
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backend's clock when it last acknowledged a write of each field ([SyncField]), for the
 * sign-in that wrote it (LWW D12). A snapshot whose `server_hlc` is not later than that may have
 * been read before the write, so a pull keeps this phone's value of the field. In memory, as on
 * iOS: a new process's first snapshot is taken after every write this one acknowledged. It reads
 * empty once its sign-in ends.
 */
@Singleton
class AckClock @Inject constructor() {
    private val acks = SignInBound<Map<String, Long>>(emptyMap())

    // A read and write of [acks].
    private val lock = Any()

    /**
     * A reply under [signIn] acknowledged a write of [field] at the backend's clock
     * [serverClockUs]. Kept only while [signIn] stands, and only when later than the one known: the
     * replies of two channels may land in either order. A value no clock can hold is ignored.
     */
    fun acknowledged(field: String, serverClockUs: Long, signIn: SignIn) {
        if (!isPlausibleClockUs(serverClockUs)) return
        synchronized(lock) {
            val known = acks.value
            if ((known[field] ?: Long.MIN_VALUE) >= serverClockUs) return
            acks.set(known + (field to serverClockUs), signIn)
        }
    }

    /** The clock of the last acknowledged write of [field], in µs; null with none under the current sign-in. */
    fun lastAck(field: String): Long? = acks.value[field]

    /** Every field whose last acknowledged write answered at [clockUs] or later, under the current sign-in. */
    fun acknowledgedSince(clockUs: Long): Set<String> = acks.value.filterValues { it >= clockUs }.keys
}

/**
 * Whether [clockUs] can be a TigerSync clock value: a time from 2020 to 2100, in µs. Anything else
 * is a broken header or a broken answer, and taken as an acknowledgment, it would hold its field
 * over every later snapshot.
 */
internal fun isPlausibleClockUs(clockUs: Long): Boolean = clockUs in EARLIEST_CLOCK_US..LATEST_CLOCK_US

// 2020-01-01T00:00:00Z and 2100-01-01T00:00:00Z, in µs.
internal const val EARLIEST_CLOCK_US = 1_577_836_800_000_000L
internal const val LATEST_CLOCK_US = 4_102_444_800_000_000L
