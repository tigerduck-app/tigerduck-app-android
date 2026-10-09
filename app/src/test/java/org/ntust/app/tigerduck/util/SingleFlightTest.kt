package org.ntust.app.tigerduck.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    /** A refresh that counts its runs and holds each one open until [release]. */
    private class Refresh {
        var runs = 0
        private var gate = CompletableDeferred<Unit>()

        suspend fun run() {
            runs++
            gate.await()
        }

        fun release() {
            gate.complete(Unit)
            gate = CompletableDeferred()
        }
    }

    private fun TestScope.flight() = SingleFlight(backgroundScope)

    @Test
    fun `callers that ask while a run is going share it`() = runTest {
        val flight = flight()
        val refresh = Refresh()
        val callers = List(3) { launch { flight.join(refresh::run) } }
        runCurrent()

        refresh.release()
        runCurrent()

        assertEquals(1, refresh.runs)
        assertTrue(callers.all { it.isCompleted })
    }

    @Test
    fun `a caller after the run finished starts a new one`() = runTest {
        val flight = flight()
        val refresh = Refresh()
        launch { flight.join(refresh::run) }
        runCurrent()
        refresh.release()
        runCurrent()

        launch { flight.join(refresh::run) }
        runCurrent()

        assertEquals(2, refresh.runs)
        refresh.release()
    }

    @Test
    fun `a rerun waits for the run in progress and then runs again`() = runTest {
        val flight = flight()
        val refresh = Refresh()
        launch { flight.join(refresh::run) }
        runCurrent()

        val rerun = launch { flight.rerun(refresh::run) }
        runCurrent()
        assertEquals("the second run must not overlap the first", 1, refresh.runs)

        refresh.release()
        runCurrent()
        assertEquals(2, refresh.runs)

        refresh.release()
        runCurrent()
        assertTrue(rerun.isCompleted)
    }

    @Test
    fun `reruns asked for during one run share the next run`() = runTest {
        val flight = flight()
        val refresh = Refresh()
        launch { flight.join(refresh::run) }
        runCurrent()

        launch { flight.rerun(refresh::run) }
        launch { flight.rerun(refresh::run) }
        runCurrent()
        refresh.release()
        runCurrent()
        refresh.release()
        runCurrent()

        assertEquals(2, refresh.runs)
    }

    @Test
    fun `a caller that is cancelled while waiting leaves the run going`() = runTest {
        val flight = flight()
        val refresh = Refresh()
        var finished = false
        val leaving = launch { flight.join { refresh.run(); finished = true } }
        val staying = launch { flight.join { error("must join the run in progress") } }
        runCurrent()

        leaving.cancel()
        refresh.release()
        runCurrent()

        assertTrue(finished)
        assertTrue(staying.isCompleted)
    }

    @Test
    fun `cancel stops the run, and the next caller starts afresh`() = runTest {
        val flight = flight()
        val refresh = Refresh()
        val first = launch { flight.join(refresh::run) }
        runCurrent()

        flight.cancel()
        runCurrent()
        assertTrue(first.isCompleted)

        launch { flight.join(refresh::run) }
        runCurrent()
        assertEquals(2, refresh.runs)
        refresh.release()
    }
}
