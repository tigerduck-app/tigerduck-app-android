package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The version a row reads to recompute its relative-time labels. It must change on every tick, on
 * every debug-clock change (even to the same time), and when the app comes back to the front, since
 * a coroutine wait does not count the time a phone spends in deep sleep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppClockVersionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // 2026-11-05T09:10 in Taipei (UTC+8), frozen, so only a change or a resume can move the version.
    private val frozenAt = 1_793_841_000_000L

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun freeze(at: Long) =
        AppClock.setOverride(ClockOverride(at, frozen = true, savedAtRealMillis = 0L))

    private fun showProbe(): () -> Long {
        var read = -1L
        composeRule.setContent {
            val version by rememberAppClockVersion()
            read = version
        }
        composeRule.waitForIdle()
        return { composeRule.waitForIdle(); read }
    }

    @Test
    fun `the version moves when the debug clock is set, even to the same time`() {
        freeze(frozenAt)
        val version = showProbe()
        val first = version()
        freeze(frozenAt)
        val second = version()
        assertTrue("same-time override: $first -> $second", second > first)
        freeze(frozenAt + 3_600_000)
        val third = version()
        assertTrue("moved override: $second -> $third", third > second)
    }

    @Test
    fun `the version moves when the app returns to the front`() {
        freeze(frozenAt)
        val version = showProbe()
        val before = version()
        val scenario = composeRule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        val after = version()
        assertTrue("resume: $before -> $after", after > before)
    }
}
