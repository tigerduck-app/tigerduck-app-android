package org.ntust.app.tigerduck.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.shared.StoredPasswordSchedule

/**
 * The try of a stored password the library refused, once its wait is over, so a page left on the
 * sign-in form signs in without a return to the app (tigerduck-app/tigerduck-app#769).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPasswordRetryTest {

    private val store = MemoryLibraryStore().apply { storeExpired() }
    private val server = FakeLibraryServer()
    private var tries = 0
    private var answered = 0

    private fun TestScope.now() = START + testScheduler.currentTime

    private fun refuse(at: Long) = store.refuseLibraryPassword(store.libraryAccountGeneration, "pw", at)

    /** The service and its retries on the test's clock, which [TestScope] moves as it runs the waits. */
    private fun TestScope.start(): LibraryService {
        val service = server.service(store) { now() }
        LibraryPasswordRetry(service, now = { now() }) {
            tries++
            service.retryStoredPassword().also { answered++ }
        }.start(backgroundScope)
        runCurrent()
        return service
    }

    /** Lets the tries begun finish: their sign-ins go out on threads of their own. */
    private fun TestScope.settle() {
        runCurrent()
        val deadline = System.currentTimeMillis() + 5_000
        while (answered < tries && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            runCurrent()
        }
    }

    @Test
    fun `a refusal is tried again once its wait is over, and not before`() = runTest {
        refuse(at = now())
        start()

        advanceTimeBy(9_999)
        settle()
        assertEquals(0, server.signIns)
        advanceTimeBy(1)
        settle()
        assertEquals(1, server.signIns)
        assertEquals(0, store.libraryPasswordRefusals)

        // The library took it, so nothing more goes.
        advanceTimeBy(DAY)
        settle()
        assertEquals(1, server.signIns)
    }

    @Test
    fun `each refusal of a try waits the next step of the schedule`() = runTest {
        repeat(4) { server.signInAnswers += FakeLibraryServer.WRONG_PASSWORD }
        refuse(at = now())
        start()

        listOf(10_000L, 60_000L, 300_000L, SIX_HOURS, SIX_HOURS).forEachIndexed { tried, wait ->
            advanceTimeBy(wait - 1)
            settle()
            assertEquals(tried, server.signIns)
            advanceTimeBy(1)
            settle()
            assertEquals(tried + 1, server.signIns)
        }
        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `a try the library does not answer goes again a step further down`() = runTest {
        server.signInAnswers += FakeLibraryServer.NO_NETWORK
        server.signInAnswers += FakeLibraryServer.SERVER_ERROR
        refuse(at = now())
        start()

        advanceTimeBy(10_000)
        settle()
        assertEquals(1, server.signIns)
        assertEquals(1, store.libraryPasswordRefusals)

        advanceTimeBy(StoredPasswordSchedule.delayMs(2) - 1)
        settle()
        assertEquals(1, server.signIns)
        advanceTimeBy(1)
        settle()
        assertEquals(2, server.signIns)

        advanceTimeBy(StoredPasswordSchedule.delayMs(3))
        settle()
        assertEquals(3, server.signIns)
        assertEquals(0, store.libraryPasswordRefusals)
    }

    @Test
    fun `a wait that ran out while the app was closed is tried at launch`() = runTest {
        // As a refusal kept by a build before the retries comes up.
        store.libraryPasswordRefusals = StoredPasswordSchedule.quickRetries
        store.libraryPasswordRefusedAt = 0L

        start()
        settle()

        assertEquals(1, server.signIns)
    }

    @Test
    fun `a sign-in by hand ends the wait`() = runTest {
        refuse(at = now())
        val service = start()

        runBlocking(Dispatchers.IO) { service.login("B10000001", "new", store.libraryAccountGeneration, byUser = true) }
        advanceTimeBy(DAY)
        settle()

        assertEquals(1, server.signIns)
    }

    @Test
    fun `a sign-out ends the wait`() = runTest {
        refuse(at = now())
        start()

        store.signOut()
        advanceTimeBy(DAY)
        settle()

        assertEquals(0, server.signIns)
    }

    private companion object {
        const val START = 1_000_000_000L
        const val SIX_HOURS = 6 * 60 * 60_000L
        const val DAY = 24 * 60 * 60_000L
    }
}
