package org.ntust.app.tigerduck

import java.time.ZoneId
import java.util.TimeZone

object AppConstants {
    const val APP_NAME = "TigerDuck"

    /** All "what day/time is it?" logic must use Taipei time, not the device timezone. */
    val TAIPEI_TZ: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
    val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

    object Periods {
        val defaultVisible = listOf("1", "2", "3", "4", "6", "7", "8", "9")
        val extended = listOf("5", "10", "A", "B", "C", "D")
        val chronologicalOrder = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "A", "B", "C", "D")
        val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
        val weekendDays = listOf("Sat", "Sun")
    }

    object PeriodTimes {
        val mapping: Map<String, Pair<String, String>> = mapOf(
            "1" to ("08:10" to "09:00"),
            "2" to ("09:10" to "10:00"),
            "3" to ("10:20" to "11:10"),
            "4" to ("11:20" to "12:10"),
            "5" to ("12:20" to "13:10"),
            "6" to ("13:20" to "14:10"),
            "7" to ("14:20" to "15:10"),
            "8" to ("15:30" to "16:20"),
            "9" to ("16:30" to "17:20"),
            "10" to ("17:30" to "18:20"),
            "A" to ("18:30" to "19:20"),
            "B" to ("19:25" to "20:10"),
            "C" to ("20:15" to "21:05"),
            "D" to ("21:10" to "22:00"),
        )
    }
}
