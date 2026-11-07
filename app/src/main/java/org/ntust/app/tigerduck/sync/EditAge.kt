package org.ntust.app.tigerduck.sync

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When an edit was made, by this device's clocks: the wall clock in epoch ms ([wallMs]), and, where
 * known, `SystemClock.elapsedRealtime()` ([elapsedMs]) and the boot it counts from
 * (`Settings.Global.BOOT_COUNT`, [boot]). A write sends the edit's age (`age_ms`, backend 4.3.0),
 * which [DeviceClocks.ageMs] reads at each send, and the backend stamps the edit with its arrival
 * less that age, so no device time is ever compared with the backend's.
 *
 * An edit an older build queued kept its wall time alone, and so do the oldest value ([OLDEST]) and
 * an entry given its old-entry time ([org.ntust.app.tigerduck.sync.outbox.OldEntryTime]): with no
 * [elapsedMs] or [boot], each ages by the wall clock.
 */
data class EditTime(val wallMs: Long, val elapsedMs: Long? = null, val boot: Int? = null) {

    /** Whether this and [other] were read in one known boot, so their elapsed times compare. */
    fun sameBootAs(other: EditTime): Boolean =
        boot != null && boot == other.boot && elapsedMs != null && other.elapsedMs != null

    /** Whether this came before [other]: by the elapsed clock within one boot, else by the wall clock. */
    fun isBefore(other: EditTime): Boolean =
        if (sameBootAs(other)) elapsedMs!! < other.elapsedMs!! else wallMs < other.wallMs

    /**
     * The milliseconds from this to [now]: the elapsed clock's within one boot, which a wall clock
     * set back or forward meanwhile does not move, else the wall clock's; never below 0.
     */
    fun ageAt(now: EditTime): Long =
        maxOf(0L, if (sameBootAs(now)) now.elapsedMs!! - elapsedMs!! else now.wallMs - wallMs)

    companion object {
        /**
         * The oldest edit there is (LWW D13): a value this phone held before its category ever
         * synced, which fills only a field the backend holds no stamp for. Its age is the wall
         * clock's whole reading, which the backend holds at its cap of a year.
         */
        val OLDEST = EditTime(0L)
    }
}

/**
 * This device's clocks, for an edit's time ([now]) and its age at a send ([ageMs]): the one home of
 * the `age_ms` rule. A test hands in its own wall clock, elapsed clock and boot count.
 */
@Singleton
class DeviceClocks internal constructor(
    private val wall: () -> Long,
    private val elapsed: () -> Long?,
    private val boot: () -> Int?,
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(System::currentTimeMillis, SystemClock::elapsedRealtime, bootCountOf(context))

    /** The time of an edit made now. */
    fun now(): EditTime = EditTime(wall(), elapsed(), boot())

    /**
     * The `age_ms` of an edit made at [madeAt], read now, at its send or its retry: the
     * milliseconds since, by [EditTime.ageAt]. 0 for an edit made now, which keeps no time (null).
     */
    fun ageMs(madeAt: EditTime?): Long = madeAt?.ageAt(now()) ?: 0L

    companion object {
        /** The wall clock alone, with no elapsed clock or boot: for a queue or a client a test builds without the device's. */
        val WALL = wallOnly(System::currentTimeMillis)

        /** [wall] alone, with no elapsed clock or boot, so every age is [wall]'s: for a test's queue. */
        fun wallOnly(wall: () -> Long) = DeviceClocks(wall, { null }, { null })
    }
}

// The boot count, read once: every process runs within one boot. Null where the setting cannot be
// read, which turns each age to the wall clock's.
private fun bootCountOf(context: Context): () -> Int? {
    val count by lazy {
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrNull()?.takeIf { it >= 0 }
    }
    return { count }
}
