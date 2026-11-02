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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `a change waits for the telling of the one before it`() {
        // A sign-out told after a sign-in it came before would leave a page signed out.
        val state = SignedInState(initial = false)
        val told = Collections.synchronizedList(mutableListOf<Boolean>())
        val telling = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val signIn = thread {
            state.set(true) {
                telling.countDown()
                letGo.await(5, TimeUnit.SECONDS)
                told += true
            }
        }
        telling.await(5, TimeUnit.SECONDS)
        val signOut = thread { state.set(false) { told += false } }

        signOut.join(200)
        assertTrue("it waits", signOut.isAlive)
        letGo.countDown()
        signIn.join(5_000)
        signOut.join(5_000)

        assertEquals(listOf(true, false), told.toList())
        assertFalse(state.signedIn.value)
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
