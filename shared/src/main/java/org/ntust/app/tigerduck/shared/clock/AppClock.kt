package org.ntust.app.tigerduck.shared.clock

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

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
    }

    fun currentOverride(): ClockOverride? = override
}
