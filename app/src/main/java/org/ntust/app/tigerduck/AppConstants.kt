package org.ntust.app.tigerduck

import java.time.ZoneId
import java.util.TimeZone

object AppConstants {
    const val APP_NAME = "TigerDuck"

    /** All "what day/time is it?" logic must use Taipei time, not the device timezone. */
    val TAIPEI_TZ: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
    val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

    val Periods = org.ntust.app.tigerduck.shared.Periods
    val PeriodTimes = org.ntust.app.tigerduck.shared.PeriodTimes
}
