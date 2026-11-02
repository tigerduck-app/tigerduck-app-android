package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** An account's signed-in flag, and telling of its changes in one hold with it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignedInStateTest {

    @Test
    fun `each change is told, a repeat of the same value says it changed nothing`() {
        val state = SignedInState(initial = true)
        val told = mutableListOf<Pair<Boolean, Boolean>>()

        for (value in listOf(true, false, true)) state.set(value) { changed -> told += value to changed }

        assertEquals(listOf(true to false, false to true, true to true), told)
        assertEquals(true, state.signedIn.value)
    }

    @Test
    fun `what follows a change reads the flag already changed`() {
        val state = SignedInState(initial = true)
        val flagWhenTold = mutableListOf<Boolean>()

        state.set(false) { flagWhenTold += state.signedIn.value }

        // The sign-out's effects read the account as gone, not as still signed in.
        assertEquals(listOf(false), flagWhenTold)
    }

    @Test
    fun `a change that no longer stands moves nothing and tells nothing`() {
        val state = SignedInState(initial = false)
        var told = 0

        assertFalse(state.set(true, stands = { false }) { told++ })

        assertFalse(state.signedIn.value)
        assertEquals(0, told)
        assertTrue(state.set(true, stands = { true }) { told++ })
        assertEquals(1, told)
    }

    @Test
    fun `two threads tell in the order they changed it`() {
        // Round after round the last thing told has to be what the flag reads, wherever the two
        // land: a sign-out told after a sign-in it came before would leave a page signed out.
        repeat(2_000) {
            val state = SignedInState(initial = false)
            val told = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            val start = CountDownLatch(1)
            val threads = listOf(true, false).map { value ->
                thread {
                    start.await()
                    state.set(value) { changed -> if (changed) told += value }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(state.signedIn.value, told.lastOrNull() ?: false)
        }
    }

    @Test
    fun `a page collecting the flag reads it, as the rest of the screens do`() {
        val state = SignedInState(initial = true)
        val seen = mutableListOf<Boolean>()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { state.signedIn.collect { seen += it } }

        state.set(false)

        assertEquals(listOf(true, false), seen)
        scope.cancel()
    }
}
