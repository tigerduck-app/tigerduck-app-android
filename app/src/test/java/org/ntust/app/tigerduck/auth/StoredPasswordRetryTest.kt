package org.ntust.app.tigerduck.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The try of a stored password the school refused, once its wait is over, which takes the place
 * of waiting for the user to sign in by hand (tigerduck-app/tigerduck-app#768).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredPasswordRetryTest {

    private lateinit var prefs: SharedPreferences
    private var stored: Pair<String, String>? = STUDENT to PASSWORD

    @Before
    fun setUp() {
        prefs = JvmContext().getSharedPreferences("retry-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    /** The latch on the test's clock, which [TestScope] moves as it runs the waits. */
    private fun TestScope.latch() =
        PersistedStoredPasswordLatch(prefs, now = { START + testScheduler.currentTime }) { stored }

    private fun PersistedStoredPasswordLatch.refuse() = markRejected(generation, STUDENT, PASSWORD, "Wrong password")

    /** Starts the retries with [retry] standing in for the chain, and counts its runs. */
    private fun TestScope.retries(
        latch: PersistedStoredPasswordLatch,
        retry: suspend () -> Boolean,
    ): () -> Int {
        var runs = 0
        StoredPasswordRetry(latch, now = { START + testScheduler.currentTime }) {
            runs++
            retry()
        }.start(backgroundScope)
        runCurrent()
        return { runs }
    }

    @Test
    fun `a refusal is tried again once its wait is over, and not before`() = runTest {
        val latch = latch()
        latch.refuse()
        val runs = retries(latch) { true }

        advanceTimeBy(9_999)
        assertEquals(0, runs())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runs())

        // It ran through, so nothing more goes.
        advanceTimeBy(DAY)
        assertEquals(1, runs())
    }

    @Test
    fun `each refusal of a try waits the next step of the schedule`() = runTest {
        val latch = latch()
        latch.refuse()
        val runs = retries(latch) {
            latch.refuse()
            false
        }

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, runs())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, runs())
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(3, runs())
        advanceTimeBy(21_600_000 - 1)
        assertEquals(3, runs())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, runs())
    }

    @Test
    fun `a try lost to the network goes again a step further down the schedule`() = runTest {
        val latch = latch()
        latch.refuse()
        val runs = retries(latch) { false }

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, runs())
        // One refusal and one miss: the second step.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, runs())
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(3, runs())
    }

    @Test
    fun `a post the school takes meanwhile, a sign-in by hand or a sign-out leaves nothing to try`() = runTest {
        val accepted = latch()
        accepted.refuse()
        val acceptedRuns = retries(accepted) { true }
        accepted.markAccepted(accepted.generation, STUDENT, PASSWORD)
        advanceTimeBy(DAY)
        assertEquals(0, acceptedRuns())

        accepted.refuse()
        val clearedRuns = retries(accepted) { true }
        accepted.clear()
        advanceTimeBy(DAY)
        assertEquals(0, clearedRuns())
    }

    @Test
    fun `a clear and a new refusal before the retries look still arm a try`() = runTest {
        val latch = latch()
        latch.refuse()
        val runs = retries(latch) { true }
        advanceTimeBy(10_000)
        runCurrent()
        // It ran through on live sessions, so the count stays at 1.
        assertEquals(1, runs())

        // Back to 1 with no turn for the retries in between.
        latch.clear()
        latch.refuse()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(2, runs())
    }

    @Test
    fun `an account gone by the time the wait is over is not tried`() = runTest {
        val latch = latch()
        latch.refuse()
        val runs = retries(latch) { true }

        stored = null
        advanceTimeBy(DAY)

        assertEquals(0, runs())
    }

    @Test
    fun `a wait from before a relaunch is taken up where it stands`() = runTest {
        // Refused 5 seconds before this process started.
        prefs.edit().putInt("stored_password_refusals", 1).putLong("stored_password_refused_at", START - 5_000).commit()
        val runs = retries(latch()) { true }

        advanceTimeBy(4_999)
        assertEquals(0, runs())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, runs())
    }

    @Test
    fun `a device the old latch held is tried at once`() = runTest {
        prefs.edit().putBoolean("stored_password_rejected", true).commit()

        val runs = retries(latch()) { true }

        assertEquals(1, runs())
    }

    // The chain each try runs

    @Test
    fun `a try signs in to the school, then gets a missing Moodle token, then signs in to the backend`() = runTest {
        val steps = mutableListOf<String>()

        val ranThrough = retryStoredPasswordChain(
            signInToSchool = { steps += "school"; true },
            moodleTokenMissing = { true },
            renewMoodleToken = { steps += "moodle" },
            settle = { steps += "settle" },
            signInToBackend = { renewed -> steps += "backend $renewed"; true },
        )

        assertTrue(ranThrough)
        assertEquals(listOf("school", "moodle", "settle", "backend true"), steps)
    }

    @Test
    fun `a try with a Moodle token stored leaves it`() = runTest {
        val steps = mutableListOf<String>()

        retryStoredPasswordChain(
            signInToSchool = { steps += "school"; true },
            moodleTokenMissing = { false },
            renewMoodleToken = { steps += "moodle" },
            settle = { steps += "settle" },
            signInToBackend = { renewed -> steps += "backend $renewed"; true },
        )

        assertEquals(listOf("school", "settle", "backend false"), steps)
    }

    @Test
    fun `a try stops at the first step that fails`() = runTest {
        val steps = mutableListOf<String>()

        assertFalse(
            retryStoredPasswordChain(
                signInToSchool = { steps += "school"; false },
                moodleTokenMissing = { true },
                renewMoodleToken = { steps += "moodle" },
                settle = { steps += "settle" },
                signInToBackend = { renewed -> steps += "backend $renewed"; true },
            ),
        )
        assertFalse(
            retryStoredPasswordChain(
                signInToSchool = { steps += "school"; true },
                moodleTokenMissing = { true },
                renewMoodleToken = { steps += "moodle"; error("refused") },
                settle = { steps += "settle" },
                signInToBackend = { renewed -> steps += "backend $renewed"; true },
            ),
        )
        assertFalse(
            retryStoredPasswordChain(
                signInToSchool = { true },
                moodleTokenMissing = { false },
                renewMoodleToken = {},
                settle = { steps += "settle" },
                signInToBackend = { _ -> false },
            ),
        )
        assertEquals(listOf("school", "school", "moodle", "settle"), steps)
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "old-password"
        const val START = 1_800_000_000_000L
        const val DAY = 86_400_000L
    }
}
