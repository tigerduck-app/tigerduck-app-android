package org.ntust.app.tigerduck.wear

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** F-Droid's build has no watch app, so Settings offers no switch for one. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WearCompanionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `this flavor ships no watch app`() {
        assertFalse(WearCompanion.SHIPS)
    }

    @Test
    fun `no watch is ever seen, even where a build that ships one remembered it`() = runTest {
        assertFalse(WearCompanion.hasConnectedWatch(context))
        WatchSeen(WatchSeen.prefsOf(context), ships = true) { true }.check()

        val watch = WatchSeen(context)
        watch.check()
        assertFalse(watch.seen.value)
    }
}
