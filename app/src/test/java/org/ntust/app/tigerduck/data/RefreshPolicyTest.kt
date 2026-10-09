package org.ntust.app.tigerduck.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class RefreshPolicyTest {

    private var clockMs = 1_000_000L
    private val minute = 60_000L

    private fun triggers(policy: RefreshPolicy) = RefreshTriggers(policy) { clockMs }

    private val everything = RefreshPolicy(onForeground = true, onRevisit = true)

    @Test
    fun `the default fetches on launch and on nothing else`() {
        val t = triggers(RefreshPolicy())
        assertTrue(t.onLaunch())
        assertFalse(t.onForeground())
        t.onShown()
        t.onLeft()
        clockMs += 10 * minute
        assertFalse(t.onShown())
        assertFalse(t.onForeground())
    }

    @Test
    fun `the first showing of a page is the launch, not a revisit`() {
        assertFalse(triggers(everything).onShown())
    }

    @Test
    fun `the start page shown as its launch fetch begins does not fetch again`() {
        val t = triggers(everything)
        t.fetchStarted()
        assertFalse(t.onShown())
    }

    @Test
    fun `a page first opened long after its launch fetch counts as a return`() {
        val t = triggers(everything)
        t.fetchStarted() // loaded at launch, while another page was showing
        clockMs += 10 * minute
        assertTrue(t.onShown())
    }

    @Test
    fun `a revisit fetches once the interval has passed since the last fetch`() {
        val t = triggers(everything)
        t.onShown()
        t.fetchStarted()

        t.onLeft()
        clockMs += minute - 1
        assertFalse(t.onShown())
        t.onLeft()
        clockMs += 1
        assertTrue(t.onShown())
    }

    @Test
    fun `shown again without leaving is a rebuilt activity, not a return`() {
        val t = triggers(everything)
        t.onShown()
        t.fetchStarted()
        clockMs += 10 * minute

        assertFalse(t.onShown()) // dark mode switched on
        t.onLeft()
        assertTrue(t.onShown())
    }

    @Test
    fun `any fetch restarts the interval, a pull included`() {
        val t = triggers(everything)
        t.fetchStarted()
        clockMs += 2 * minute
        t.fetchStarted() // a pull

        clockMs += minute / 2
        assertFalse(t.onForeground())
    }

    @Test
    fun `a page that has not fetched yet is not held back`() {
        assertTrue(triggers(everything).onForeground())
    }

    @Test
    fun `each page keeps its own interval`() {
        val t = triggers(everything.copy(minInterval = 5.minutes))
        t.fetchStarted()
        clockMs += 2 * minute
        assertFalse(t.onForeground())
        clockMs += 3 * minute
        assertTrue(t.onForeground())
    }

    @Test
    fun `background cannot be set faster than WorkManager runs`() {
        assertThrows(IllegalArgumentException::class.java) {
            RefreshPolicy(background = 10.minutes)
        }
    }

    @Test
    fun `hasPassed treats never and a future time as passed`() {
        assertTrue(RefreshPolicy.hasPassed(0L, clockMs, 15.minutes))
        assertTrue(RefreshPolicy.hasPassed(clockMs + minute, clockMs, 15.minutes))
        assertFalse(RefreshPolicy.hasPassed(clockMs - minute, clockMs, 15.minutes))
    }
}
