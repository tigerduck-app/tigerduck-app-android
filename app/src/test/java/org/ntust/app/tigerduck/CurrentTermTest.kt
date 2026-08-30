package org.ntust.app.tigerduck

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDate
import java.time.LocalDateTime

class CurrentTermTest {

    @After
    fun tearDown() = AppClock.setOverride(null)

    private fun freezeAt(dateTime: LocalDateTime) {
        val millis = dateTime.atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(
            ClockOverride(
                instantMillis = millis,
                savedAtRealMillis = System.currentTimeMillis(),
                frozen = true,
            )
        )
    }

    @Test
    fun `the day before term start is out of session`() {
        freezeAt(LocalDate.of(2026, 9, 6).atTime(23, 59))
        assertFalse(AppConstants.CurrentTerm.isInSession())
    }

    @Test
    fun `midnight on the first day of classes is in session`() {
        freezeAt(LocalDate.of(2026, 9, 7).atStartOfDay())
        assertTrue(AppConstants.CurrentTerm.isInSession())
    }

    @Test
    fun `the last day of classes stays in session up to midnight`() {
        freezeAt(LocalDate.of(2026, 12, 25).atTime(23, 59))
        assertTrue(AppConstants.CurrentTerm.isInSession())
    }

    @Test
    fun `the end bound is exclusive`() {
        freezeAt(LocalDate.of(2026, 12, 26).atStartOfDay())
        assertFalse(AppConstants.CurrentTerm.isInSession())
    }

    @Test
    fun `the window is a real range, not a fail-closed sentinel`() {
        // A date that fails to build lands at Long.MAX_VALUE, which would make
        // isInSession false forever and silently hide today-scoped surfaces.
        assertTrue(AppConstants.CurrentTerm.START < AppConstants.CurrentTerm.END)
        assertTrue(AppConstants.CurrentTerm.END < Long.MAX_VALUE)
    }

    // containsDate — the per-day gate the notification paths use. 開學 is
    // 2026-09-07, and nothing class-shaped may fire before it.

    @Test
    fun `the day before 開學 is outside the term`() {
        assertFalse(AppConstants.CurrentTerm.containsDate(LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `開學日 itself is inside the term`() {
        assertTrue(AppConstants.CurrentTerm.containsDate(LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `the last day of classes is inside the term`() {
        assertTrue(AppConstants.CurrentTerm.containsDate(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `the day after the last day of classes is outside the term`() {
        assertFalse(AppConstants.CurrentTerm.containsDate(LocalDate.of(2026, 12, 26)))
    }

    /**
     * The regression this gate exists for: the timetable is already cached in
     * late August because 選課 runs weeks ahead, and the class-preparing
     * scheduler arms alarms several days out — so every pre-開學 day it can
     * reach must read false.
     */
    @Test
    fun `no day in the pre-開學 scheduling window is in the term`() {
        val firstDay = LocalDate.of(2026, 9, 7)
        for (offset in 1..14) {
            val date = firstDay.minusDays(offset.toLong())
            assertFalse(
                "$date is before 開學 and must not schedule class notifications",
                AppConstants.CurrentTerm.containsDate(date),
            )
        }
    }

    @Test
    fun `containsDate does not depend on the debug clock`() {
        // Unlike isInSession, this answers "is that date in the term", so a
        // frozen clock must not change the verdict for a given date.
        freezeAt(LocalDate.of(2026, 8, 30).atTime(9, 0))
        assertTrue(AppConstants.CurrentTerm.containsDate(LocalDate.of(2026, 9, 7)))
        assertFalse(AppConstants.CurrentTerm.containsDate(LocalDate.of(2026, 9, 6)))
    }
}
