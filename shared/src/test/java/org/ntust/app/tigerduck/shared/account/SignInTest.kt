package org.ntust.app.tigerduck.shared.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SignInTest {

    @Test
    fun `a sign-in read while the series is closed is never current, before or after open`() {
        val series = SignInSeries(closed = true)
        val readWhileClosed = series.current
        assertFalse(readWhileClosed.isCurrent)

        series.open()

        assertFalse(readWhileClosed.isCurrent)
        assertTrue(series.current.isCurrent)
        assertNotSame(readWhileClosed, series.current)
    }

    @Test
    fun `end twice ends one sign-in, and an ended sign-in never comes back`() {
        val series = SignInSeries()
        val first = series.current

        assertSame(first, series.end())
        assertSame(first, series.end())
        assertFalse(first.isCurrent)
        assertFalse(series.isOpen.value)
        // The closed series answers with the sign-in that ended.
        assertSame(first, series.current)

        series.open()
        series.open()
        val second = series.current
        assertTrue(second.isCurrent)
        assertFalse(first.isCurrent)
        assertEquals("a second open() started nothing", second, series.current)
        assertTrue(series.isOpen.value)
    }

    @Test
    fun `the time signed out shares the next sign-in`() {
        val series = SignInSeries()
        series.end()
        val whileSignedOut = series.current
        series.open()

        // A write made while signed out is legal in the sense that nothing numbered it: it is
        // simply not the sign-in that opens next.
        assertFalse(whileSignedOut.isCurrent)
        assertTrue(series.current.isCurrent)
    }

    @Test
    fun `ifCurrent runs the block for a current sign-in and returns its value`() {
        val series = SignInSeries()
        assertEquals(7, series.current.ifCurrent { 7 })
    }

    @Test
    fun `ifCurrent never runs after the sign-in ended`() {
        val series = SignInSeries()
        val signIn = series.current
        series.end()
        var ran = false

        assertNull(signIn.ifCurrent { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `ifCurrent loses to a racing end and never runs after it`() {
        repeat(200) {
            val series = SignInSeries()
            val signIn = series.current
            val ranAfterEnd = AtomicInteger(0)
            val ended = java.util.concurrent.atomic.AtomicBoolean(false)
            val start = CountDownLatch(1)
            val workers = List(4) {
                thread {
                    start.await()
                    repeat(500) {
                        signIn.ifCurrent {
                            // end() has not returned while a block runs: it waits for the lock.
                            if (ended.get()) ranAfterEnd.incrementAndGet()
                        }
                    }
                }
            }
            val ender = thread {
                start.await()
                series.end()
                ended.set(true)
            }
            start.countDown()
            (workers + ender).forEach { it.join() }
            assertEquals(0, ranAfterEnd.get())
            assertFalse(signIn.isCurrent)
        }
    }

    @Test
    fun `awaitOpen returns at once when the series is open`() = runTest {
        SignInSeries().awaitOpen()
    }

    @Test
    fun `awaitOpen resumes on open`() = runTest {
        val series = SignInSeries(closed = true)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            series.awaitOpen()
            "open"
        }
        assertFalse(waiting.isCompleted)

        series.open()

        assertEquals("open", waiting.await())
    }

    @Test
    fun `a cancelled awaitOpen leaves no waiter, and the next sign-in is free`() = runTest {
        val series = SignInSeries(closed = true)
        val started = CompletableDeferred<Unit>()
        val waiting = launch {
            started.complete(Unit)
            series.awaitOpen()
        }
        started.await()
        waiting.cancelAndJoin()

        assertEquals(0, series.waiters)
        series.open()
        series.awaitOpen()
        assertTrue(series.current.isCurrent)
    }

    @Test
    fun `a bound value reads empty after its sign-in ends`() {
        val series = SignInSeries()
        val bound = SignInBound("none")
        bound.set("first", series.current)
        assertEquals("first", bound.value)

        series.end()
        assertEquals("none", bound.value)
        series.open()
        assertEquals("none", bound.value)
    }

    @Test
    fun `a bound value ignores a set from an ended sign-in`() {
        val series = SignInSeries()
        val old = series.current
        series.end()
        series.open()
        val bound = SignInBound("none")

        bound.set("stale", old)
        assertEquals("none", bound.value)
        bound.set("fresh", series.current)
        assertEquals("fresh", bound.value)
    }

    @Test
    fun `a demo sign-in keeps its flag through end, and leaveDemo makes the next one plain`() {
        val series = SignInSeries()
        assertFalse(series.current.isDemo)

        series.startDemo()
        val demo = series.current
        assertTrue(demo.isDemo)
        assertTrue(demo.isCurrent)

        series.end()
        assertTrue("an ended demo sign-in is still the demo's", demo.isDemo)
        // Until the demo is left, the next one is the demo's too.
        series.open()
        assertTrue(series.current.isDemo)

        series.end()
        series.leaveDemo()
        series.open()
        assertFalse(series.current.isDemo)
    }

    @Test
    fun `startDemo ends the sign-in it replaces`() {
        val series = SignInSeries()
        val plain = series.current
        series.startDemo()
        assertFalse(plain.isCurrent)
        assertTrue(series.current.isDemo)
    }

    @Test
    fun `two sign-ins of the same series are never equal`() {
        val series = SignInSeries()
        val first = series.current
        series.end()
        series.open()
        assertFalse(first == series.current)
    }
}
