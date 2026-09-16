package org.ntust.app.tigerduck.mail.sync

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
