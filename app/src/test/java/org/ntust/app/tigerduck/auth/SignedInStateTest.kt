package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/** The account's signed-in flag and its counted changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignedInStateTest {

    @Test
    fun `each change is counted, a repeat of the same value is not`() {
        val state = SignedInState(initial = true)

        state.set(true)
        state.set(false)
        state.set(true)

        assertEquals(AccountState(signedIn = true, changes = 2), state.counted.value)
        assertEquals(true, state.signedIn.value)
    }

    @Test
    fun `what follows a counted change reads the flag already changed`() {
        val state = SignedInState(initial = true)
        val flagWhenCounted = mutableListOf<Boolean>()
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { state.counted.collect { flagWhenCounted += state.signedIn.value } }

        state.set(false)

        // The sign-out's effects read the account as gone, not as still signed in.
        assertEquals(listOf(true, false), flagWhenCounted)
        scope.cancel()
    }
}
