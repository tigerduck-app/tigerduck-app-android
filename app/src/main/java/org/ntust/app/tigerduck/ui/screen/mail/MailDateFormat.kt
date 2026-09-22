package org.ntust.app.tigerduck.ui.screen.mail

import org.ntust.app.tigerduck.shared.clock.AppClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Today's mail shows HH:mm, older mail M/d (spec §6.2) — Taipei time like the rest of the app. */
object MailDateFormat {
    private val ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("M/d")
    private val FULL = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

    fun short(instant: Instant?, now: Instant = AppClock.instant()): String {
        if (instant == null) return ""
        val date = instant.atZone(ZONE)
        return if (date.toLocalDate() == now.atZone(ZONE).toLocalDate()) TIME.format(date) else DAY.format(date)
    }

    fun full(instant: Instant?): String = instant?.atZone(ZONE)?.let(FULL::format).orEmpty()
}
