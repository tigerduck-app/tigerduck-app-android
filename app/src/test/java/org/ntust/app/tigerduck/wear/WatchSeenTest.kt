package org.ntust.app.tigerduck.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings' "Gradient on Wear OS" waits for a watch: hidden on a phone that has never had one
 * connected, shown once one has, and kept through the watch being out of reach after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchSeenTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("watch_seen_test", Context.MODE_PRIVATE)
    private var connected: suspend () -> Boolean = { false }
    private var asks = 0

    private fun watch(ships: Boolean = true) = WatchSeen(prefs, ships) {
        asks++
        connected()
    }

    @Test
    fun `a phone that never had a watch connected shows no row`() = runTest {
        val watch = watch()
        watch.check()

        assertFalse(watch.seen.value)
        assertEquals(1, asks)
    }

    @Test
    fun `a watch connected shows the row, and it stays once the watch is out of reach`() = runTest {
        connected = { true }
        val watch = watch()
        assertFalse(watch.seen.value)
        watch.check()
        assertTrue(watch.seen.value)

        connected = { false }
        watch.check()
        assertTrue(watch.seen.value)
        // The next time the app starts, the watch still away: remembered, and not asked again.
        val later = watch()
        assertTrue(later.seen.value)
        later.check()
        assertTrue(later.seen.value)
        assertEquals(1, asks)
    }

    @Test
    fun `Play services out of reach is no watch yet, and never throws`() = runTest {
        connected = { throw IllegalStateException("API_UNAVAILABLE") }
        val watch = watch()
        watch.check()
        assertFalse(watch.seen.value)

        connected = { true }
        watch.check()
        assertTrue(watch.seen.value)
    }

    @Test
    fun `a look that never answers is no watch yet`() = runTest {
        connected = { awaitCancellation() }
        val watch = watch()
        watch.check()

        assertFalse(watch.seen.value)
    }

    @Test
    fun `a build without the watch app never shows the row, nor looks for a watch`() = runTest {
        connected = { true }
        watch().check()

        val fdroid = watch(ships = false)
        fdroid.check()
        assertFalse(fdroid.seen.value)
        assertEquals(1, asks)
    }
}
