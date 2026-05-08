package org.ntust.app.tigerduck.shared.clock

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of "now" for the app. All UI / class-status / scheduler code
 * MUST read time through this object so the debug override applies uniformly.
 *
 * Auth/network code (session expiry, cookie TTL, login timestamps, cache TTLs)
 * intentionally does NOT use AppClock — see spec for rationale.
 */
object AppClock {

    @Volatile
    private var override: ClockOverride? = null

    private val versionCounter = AtomicLong(0L)

    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()

    /**
     * Monotonically increasing counter bumped on every [setOverride] call.
     * UI/ViewModel layers can use this as a key/Flow input so anything derived
     * from "now" recomputes when the debug clock toggles — without forcing the
     * shared module to depend on coroutines or Compose.
     */
    fun version(): Long = versionCounter.get()

    /** Add a listener that fires (with the new version) on each [setOverride] call. */
    fun addOverrideListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeOverrideListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    fun nowMillis(): Long {
        val o = override ?: return System.currentTimeMillis()
        return if (o.frozen) {
            o.instantMillis
        } else {
            o.instantMillis + (System.currentTimeMillis() - o.savedAtRealMillis)
        }
    }

    fun instant(): Instant = Instant.ofEpochMilli(nowMillis())

    fun localDateTime(zone: ZoneId = ZoneId.of("Asia/Taipei")): LocalDateTime =
        LocalDateTime.ofInstant(instant(), zone)

    fun calendar(tz: TimeZone = TimeZone.getTimeZone("Asia/Taipei")): Calendar {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = nowMillis()
        return cal
    }

    /**
     * Translate a target instant in the app's clock (possibly fake) into the
     * real wall-clock instant at which it should occur. Used as the trigger
     * arg to AlarmManager.RTC_WAKEUP so alarms fire at the right *real*
     * moment under fake time.
     *
     * Identity when no override is active.
     *
     * Not idempotent in frozen mode: real-now keeps moving while fake-now
     * stays put, so two calls for the same target return different values.
     * Capture the result once at scheduling time; do not re-call it for the
     * same target.
     */
    fun realTimeFor(appWallMillis: Long): Long {
        val o = override ?: return appWallMillis
        return if (o.frozen) {
            System.currentTimeMillis() + (appWallMillis - o.instantMillis)
        } else {
            appWallMillis - (o.instantMillis - o.savedAtRealMillis)
        }
    }

    fun setOverride(override: ClockOverride?) {
        this.override = override
        val v = versionCounter.incrementAndGet()
        for (l in listeners) l(v)
    }

    fun currentOverride(): ClockOverride? = override
}
