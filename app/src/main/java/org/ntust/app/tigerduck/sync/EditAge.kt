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
 * An edit an older build queued kept its wall time alone, and so does an entry given its old-entry
 * time ([org.ntust.app.tigerduck.sync.outbox.OldEntryTime]): with no [elapsedMs] or [boot], each
 * ages by the wall clock. The oldest value ([OLDEST]) has no age at all: it goes as `hlc: 0`
 * ([DeviceClocks.stampOf]).
 */
data class EditTime(val wallMs: Long, val elapsedMs: Long? = null, val boot: Int? = null) {

    /** Whether this is the oldest edit there is ([OLDEST]), the one way to say so. */
    val isOldest: Boolean get() = this == OLDEST

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
         * synced, an add an upload names with no time, an old-entry time of 0. It fills only a
         * field the backend holds no stamp for, so it goes as the stamp 0 ([EditStamp.Oldest]),
         * never as an age, which the backend would date at most a year back.
         */
        val OLDEST = EditTime(0L)
    }
}

/**
 * What a write sends to date an edit (backend 4.3.0): its age ([Age], `age_ms`), or for the oldest
 * value ([EditTime.OLDEST]) the stamp 0 ([Oldest], `hlc: 0`), as before `age_ms`, which loses to
 * every stamp the backend holds; the backend keeps taking `hlc` for zero stamps. [ageMs] and [hlc]
 * are the two fields, each null where the stamp sends none, so a write sends exactly one.
 */
sealed interface EditStamp {
    val ageMs: Long?
    val hlc: Long?

    /** An edit's age at this send, in ms. */
    data class Age(override val ageMs: Long) : EditStamp {
        override val hlc: Long? get() = null
    }

    /** The oldest value: the one place it turns into `hlc: 0`. */
    data object Oldest : EditStamp {
        override val ageMs: Long? get() = null
        override val hlc: Long get() = 0L
    }
}

/**
 * This device's clocks, for an edit's time ([now]) and what a write sends to date it ([stampOf],
 * from its age, [ageMs]): the one home of the `age_ms` rule. A test hands in its own wall clock,
 * elapsed clock and boot count.
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

    /** What a write sends for an edit made at [madeAt], read now: [EditStamp.Oldest] for the oldest, else its [ageMs]. */
    fun stampOf(madeAt: EditTime?): EditStamp = if (madeAt?.isOldest == true) EditStamp.Oldest else EditStamp.Age(ageMs(madeAt))

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
