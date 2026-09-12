package org.ntust.app.tigerduck.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [isRegistrationRetryDue] and [registrationRetryBackoffMillis] — the
 * rules behind [PushRegistrationService.retryRegistrationIfDue], the
 * automatic retry of a device registration that has not landed.
 *
 * [PushRegistrationService] can't be constructed here (real OkHttp /
 * Keystore dependencies, and no Robolectric or mocking library in this
 * module), so this exercises the pure rules it delegates to, with the flavor
 * passed explicitly for the reason [effectiveServerPushOptedOut]'s tests
 * give.
 */
class RegistrationRetryTest {

    /** Every input set so a retry is due; each test changes the one it is about. */
    private fun due(
        hasCompletedOnboarding: Boolean = true,
        registrationOwed: Boolean = true,
        registrationPending: Boolean = false,
        consecutiveFailures: Int = 1,
        millisSinceLastAttempt: Long = 60 * 60_000L,
        flavor: String = "play",
    ): Boolean = isRegistrationRetryDue(
        hasCompletedOnboarding = hasCompletedOnboarding,
        registrationOwed = registrationOwed,
        registrationPending = registrationPending,
        consecutiveFailures = consecutiveFailures,
        millisSinceLastAttempt = millisSinceLastAttempt,
        flavor = flavor,
    )

    @Test
    fun `a registration that failed is retried once its backoff has passed`() {
        assertTrue("a warm process must not wait for its own death to register", due())
    }

    @Test
    fun `nothing is retried before the user has been through onboarding and the privacy page`() {
        assertFalse(
            "no device identity may leave the device before consent, retry or not",
            due(hasCompletedOnboarding = false),
        )
    }

    @Test
    fun `fdroid never retries a registration`() {
        assertFalse("fdroid has no FCM token and never registers", due(flavor = "fdroid"))
    }

    /** What a process starts with: one registration owed, nothing failed yet. */
    private val atStart = RegistrationAttemptState(registrationOwed = true, consecutiveFailures = 0)

    @Test
    fun `the retry is a no-op once a registration has landed`() {
        val after = atStart.copy(consecutiveFailures = 2).afterRegistrationAttempt(landed = true)

        assertFalse("a landed registration pays off the debt the rule reads", after.registrationOwed)
        assertEquals("...and clears the streak that was spacing the retries out", 0, after.consecutiveFailures)
        assertFalse(
            "so nothing retries on top of it",
            due(registrationOwed = after.registrationOwed, consecutiveFailures = after.consecutiveFailures),
        )
    }

    @Test
    fun `a failed registration keeps the debt and spaces the next attempt further out`() {
        val once = atStart.afterRegistrationAttempt(landed = false)
        val twice = once.afterRegistrationAttempt(landed = false)

        assertTrue("a failure leaves the registration owed, or nothing would retry it", once.registrationOwed)
        assertTrue(
            "each failure in a row must push the next attempt further out",
            registrationRetryBackoffMillis(twice.consecutiveFailures) >
                registrationRetryBackoffMillis(once.consecutiveFailures),
        )
    }

    @Test
    fun `the consent gate owes a registration without spacing out the one that follows it`() {
        // Counted from a streak already running: the ladder's first two rungs
        // are both 30 s, so starting from zero would hide a stray increment.
        val running = atStart.copy(consecutiveFailures = 1)
        val after = running.afterRegistrationAttempt(landed = false, countsAsFailure = false)

        assertTrue("a device that has not consented still owes a registration", after.registrationOwed)
        assertEquals("a gate no retry can open must not count as a failure", 1, after.consecutiveFailures)
        assertEquals(
            "...so it must not climb the backoff ladder either",
            registrationRetryBackoffMillis(running.consecutiveFailures),
            registrationRetryBackoffMillis(after.consecutiveFailures),
        )
    }

    @Test
    fun `a retry is refused while a registration is already pending`() {
        // What "pending" means -- debounceJob spanning the 250 ms coalescing
        // window and the POST behind it -- lives in
        // PushRegistrationService.scheduleRegister, which this module cannot
        // construct. This pins the half that is here: given the flag, the
        // rule refuses.
        assertFalse(due(registrationPending = true))
    }

    @Test
    fun `the retry backs off instead of firing on every trigger`() {
        assertFalse(
            "the startup registration gets time to land before anything retries it",
            due(consecutiveFailures = 0, millisSinceLastAttempt = 5_000L),
        )
        assertFalse(
            "a resume seconds after a failure must not retry yet",
            due(consecutiveFailures = 1, millisSinceLastAttempt = 10_000L),
        )
        assertTrue(due(consecutiveFailures = 1, millisSinceLastAttempt = 30_000L))
        assertFalse(
            "a second failure in a row waits longer than the first",
            due(consecutiveFailures = 2, millisSinceLastAttempt = 60_000L),
        )
        assertFalse(due(consecutiveFailures = 5, millisSinceLastAttempt = 10 * 60_000L))
        assertTrue(due(consecutiveFailures = 5, millisSinceLastAttempt = 30 * 60_000L))
    }

    @Test
    fun `the backoff grows with each failure and then stops growing`() {
        assertTrue(registrationRetryBackoffMillis(2) > registrationRetryBackoffMillis(1))
        assertTrue(registrationRetryBackoffMillis(3) > registrationRetryBackoffMillis(2))
        assertEquals(
            "a device offline for a week retries every half hour, not ever more rarely",
            registrationRetryBackoffMillis(3),
            registrationRetryBackoffMillis(100),
        )
    }
}
