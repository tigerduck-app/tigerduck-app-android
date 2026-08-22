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
}
