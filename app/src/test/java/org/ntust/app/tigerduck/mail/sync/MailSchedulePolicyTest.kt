package org.ntust.app.tigerduck.mail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailError

class MailSchedulePolicyTest {
    private fun decide(
        signedIn: Boolean = true, demo: Boolean = false, enabled: Boolean = true,
        authFailed: Boolean = false, canExact: Boolean = true,
    ) = MailSchedulePolicy.decide(signedIn, demo, enabled, authFailed, canExact)

    @Test
    fun `both mechanisms run for a normal signed-in account`() {
        assertEquals(ScheduleDecision(alarm = true, worker = true), decide())
    }

    @Test
    fun `without exact alarms only WorkManager runs`() {
        assertEquals(ScheduleDecision(alarm = false, worker = true), decide(canExact = false))
    }

    @Test
    fun `nothing runs when signed out, in demo, turned off or after an auth failure`() {
        val off = ScheduleDecision(alarm = false, worker = false)
        assertEquals(off, decide(signedIn = false))
        assertEquals(off, decide(demo = true))
        assertEquals(off, decide(enabled = false))
        assertEquals(off, decide(authFailed = true))
    }

    @Test
    fun `an exact alarm refused between the check and the call still leaves the WorkManager backstop`() {
        // The grant can be revoked while schedule() runs; the SecurityException must not reach
        // sign-in, the settings toggle or a boot broadcast, and the 15-minute backstop stays.
        var periodic = 0
        val armed = applySchedule(
            ScheduleDecision(alarm = true, worker = true),
            setAlarm = { throw SecurityException("caller needs SCHEDULE_EXACT_ALARM") },
            cancelAlarm = { throw AssertionError("must not cancel while the decision says to arm") },
            schedulePeriodic = { periodic++ },
            cancelPeriodic = { throw AssertionError("must not cancel the backstop") },
        )
        assertFalse(armed)
        assertEquals(1, periodic)
    }

    @Test
    fun `a decision without an alarm cancels it and keeps only the backstop`() {
        var cancelled = 0
        var periodic = 0
        val armed = applySchedule(
            ScheduleDecision(alarm = false, worker = true),
            setAlarm = { throw AssertionError("must not arm") },
            cancelAlarm = { cancelled++ },
            schedulePeriodic = { periodic++ },
            cancelPeriodic = { throw AssertionError("must not cancel the backstop") },
        )
        assertFalse(armed)
        assertEquals(1, cancelled)
        assertEquals(1, periodic)
    }

    @Test
    fun `only a hand-off run retries, and only when the abandoned alarm check still holds the lock`() {
        assertTrue(MailSchedulePolicy.shouldRetry(CheckOutcome.Busy, handOff = true))
        assertFalse(MailSchedulePolicy.shouldRetry(CheckOutcome.Busy, handOff = false))
        assertFalse(MailSchedulePolicy.shouldRetry(CheckOutcome.NoChange, handOff = true))
        assertFalse(MailSchedulePolicy.shouldRetry(CheckOutcome.Failed(MailError.Network()), handOff = true))
    }
}
