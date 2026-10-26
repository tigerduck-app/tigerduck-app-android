package org.ntust.app.tigerduck.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TigerSync turned off and straight back on sent its two PATCHes at once, and
 * the server kept whichever landed last ([SwitchPatches]).
 */
class SwitchPatchesTest {

    private val sent = mutableListOf<Boolean>()

    @Test
    fun `a flip waits for the PATCH before it, so the latest lands last`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val patches = SwitchPatches { enabled ->
            // The first PATCH is slow to land.
            if (calls++ == 0) release.await()
            sent += enabled
        }

        val off = patches.request()
        val first = launch { patches.send(off, false) }
        yield()
        val on = patches.request()
        val second = launch { patches.send(on, true) }
        yield()
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(false, true), sent)
        assertTrue(patches.isLatest(on))
    }

    @Test
    fun `a flip a later one overtook before it went is not sent`() = runTest {
        val patches = SwitchPatches { sent += it }

        val off = patches.request()
        val on = patches.request()

        assertFalse(patches.send(off, false))
        assertTrue(patches.send(on, true))
        assertEquals(listOf(true), sent)
        assertFalse(patches.isLatest(off))
    }

    @Test
    fun `turning TigerSync on is recorded only if it was not turned off meanwhile`() {
        val patches = SwitchPatches { sent += it }

        val on = patches.request()
        assertTrue(patches.isLatest(on))
        patches.request()
        assertFalse(patches.isLatest(on))
    }
}
