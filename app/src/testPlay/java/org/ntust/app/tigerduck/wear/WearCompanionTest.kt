package org.ntust.app.tigerduck.wear

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The play flavor ships the Wear OS app, so Settings offers the watch's gradient switch once a watch is seen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WearCompanionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `this flavor ships the watch app`() {
        assertTrue(WearCompanion.SHIPS)
    }

    // A phone without Play services' Wear OS layer, as Robolectric's is: no watch, and no crash.
    @Test
    fun `a phone Play services cannot answer for has seen no watch`() = runTest {
        val watch = WatchSeen(context)
        watch.check()
        assertFalse(watch.seen.value)
    }
}
