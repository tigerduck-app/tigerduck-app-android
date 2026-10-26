package org.ntust.app.tigerduck.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The waits between automatic posts of a stored password the school refused, in place of the latch
 * that held them off until the user signed in by hand (tigerduck-app/tigerduck-app#768): 10
 * seconds, 1 minute and 5 minutes after the first three refusals in a row, then 6 hours each time.
 */
class StoredPasswordScheduleTest {

    private lateinit var prefs: SharedPreferences
    private var stored: Pair<String, String>? = STUDENT to PASSWORD
    private var clock = 1_800_000_000_000L

    @Before
    fun setUp() {
        prefs = JvmContext().getSharedPreferences("schedule-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun latch() = PersistedStoredPasswordLatch(prefs, now = { clock }) { stored }

    private fun PersistedStoredPasswordLatch.refuse() = markRejected(generation, STUDENT, PASSWORD, "Wrong password")

    @Test
    fun `the waits are 10 seconds, 1 minute, 5 minutes, then 6 hours each time`() {
        assertEquals(
            listOf(10_000L, 60_000L, 300_000L, 21_600_000L, 21_600_000L),
            (1..5).map(StoredPasswordSchedule::delayMs),
        )
    }

    @Test
    fun `each refusal holds the next automatic post back for its wait, from the refusal`() {
        val latch = latch()
        assertFalse(latch.postMustWait())

        listOf(10_000L, 60_000L, 300_000L, 21_600_000L, 21_600_000L).forEach { wait ->
            latch.refuse()
            assertEquals(clock + wait, latch.retryAt())
            clock += wait - 1
            assertTrue(latch.postMustWait())
            clock += 1
            assertFalse(latch.postMustWait())
        }
        assertEquals(5, latch.refusals.value)
    }

    @Test
    fun `a clock set back before the refusal lets the post go`() {
        val latch = latch()
        latch.refuse()

        clock -= 60_000

        assertFalse(latch.postMustWait())
        assertEquals(clock, latch.retryAt())
    }

    @Test
    fun `the count and its wait last into the next process`() {
        latch().apply { refuse(); refuse() }

        val restarted = latch()

        assertEquals(2, restarted.refusals.value)
        assertEquals(clock + 60_000, restarted.retryAt())
        assertTrue(restarted.isRejected)
    }

    @Test
    fun `a post the school takes ends the count and the notice, in this process and the next`() {
        val latch = latch()
        latch.refuse()
        latch.refuse()

        latch.markAccepted(latch.generation, STUDENT, PASSWORD)

        assertEquals(0, latch.refusals.value)
        assertNull(latch.retryAt())
        assertNull(latch.rejection.value)
        assertEquals(0, latch().refusals.value)
        assertFalse(latch().isRejected)
        // The next refusal starts the quick ones again.
        latch.refuse()
        assertEquals(clock + 10_000, latch.retryAt())
    }

    @Test
    fun `an accepted post of a password no longer stored, or after a clear, ends nothing`() {
        val latch = latch()
        val before = latch.generation
        latch.refuse()
        stored = STUDENT to "new-password"
        latch.markAccepted(latch.generation, STUDENT, PASSWORD)
        assertEquals(1, latch.refusals.value)

        stored = STUDENT to PASSWORD
        latch.markAccepted(before - 1, STUDENT, PASSWORD)
        assertEquals(1, latch.refusals.value)
    }

    @Test
    fun `a sign-in by hand or a sign-out ends the count`() {
        val latch = latch()
        latch.refuse()
        latch.refuse()

        latch.clear()

        assertEquals(0, latch.refusals.value)
        assertFalse(latch.postMustWait())
        assertEquals(0, latch().refusals.value)
    }

    @Test
    fun `with no account stored nothing waits`() {
        val latch = latch()
        latch.refuse()

        stored = null

        assertFalse(latch.postMustWait())
        assertNull(latch.retryAt())
    }

    @Test
    fun `later refusals keep the notice of the first, so a banner closed for it stays closed`() {
        val latch = latch()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, "Wrong account or password")
        val first = latch.rejection.value
        clock += 10_000

        latch.markRejected(latch.generation, STUDENT, PASSWORD, "Locked")

        assertSame(first, latch.rejection.value)
        assertEquals(2, latch.refusals.value)
    }

    @Test
    fun `a device the old latch held tries once at once, then every 6 hours`() {
        // What the latch wrote before the retries.
        prefs.edit().putBoolean("stored_password_rejected", true)
            .putString("stored_password_rejected_reason", "Wrong account or password").commit()

        val latch = latch()

        assertFalse(latch.postMustWait())
        assertTrue(latch.isRejected)
        assertEquals("Wrong account or password", latch.rejection.value?.reason)
        latch.refuse()
        assertEquals(clock + 21_600_000, latch.retryAt())
        assertFalse(prefs.contains("stored_password_rejected"))
    }

    @Test
    fun `an automatic harvest waits out the refusal, then posts`() = runTest {
        val latch = latch()
        latch.refuse()
        var posted = 0
        suspend fun harvest() = runCatching {
            latch.guardAutomaticHarvest(STUDENT, PASSWORD) { posting -> posting.post { posted++ } }
        }

        assertTrue(harvest().exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(0, posted)

        clock += 10_000
        assertTrue(harvest().isSuccess)
        assertEquals(1, posted)
        assertEquals(0, latch.refusals.value)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a harvest that got its token without a post leaves the count`() = runTest {
        val latch = latch()
        latch.refuse()
        clock += 10_000

        latch.guardAutomaticHarvest(STUDENT, PASSWORD) { _ -> "token" }

        assertEquals(1, latch.refusals.value)
        assertTrue(latch.isRejected)
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "old-password"
    }
}
