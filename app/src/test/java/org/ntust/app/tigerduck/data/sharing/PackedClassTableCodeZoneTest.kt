package org.ntust.app.tigerduck.data.sharing

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * PackedClassTableCode used a fixed +08:00 for its academic year. It now reads Taipei's zone;
 * this pins that the two agree for every day (at the day's first and last second) from the
 * format's epoch to the last date its 24-bit time can hold.
 */
class PackedClassTableCodeZoneTest {

    @Test
    fun `Taipei is plus eight for every date a share time can hold`() {
        val plusEight = ZoneOffset.ofHours(8)
        var day = LocalDate.of(2023, 12, 31)
        val last = LocalDate.of(2055, 12, 31)
        while (!day.isAfter(last)) {
            for (second in listOf(0L, 86_399L)) {
                val at: Instant = day.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(second)
                assertEquals("$day +$second", plusEight, TaipeiClock.zone.rules.getOffset(at))
            }
            day = day.plusDays(1)
        }
    }
}
