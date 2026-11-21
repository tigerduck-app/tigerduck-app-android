package org.ntust.app.tigerduck.wear.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The countdown under the class going on on Now & Next, as the iPhone's `ClassCountdown`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassCountdownTest {

    @get:Rule
    val rule = createComposeRule()

    /** 10:20 Taipei, the start of a class until 12:10. */
    private val start = Instant.parse("2026-11-02T02:20:00Z").toEpochMilli()
    private val end = start + 110 * 60_000L

    private fun at(ms: Long) = AppClock.setOverride(ClockOverride(ms, frozen = true, savedAtRealMillis = 0L))

    /** [ms] and a frame more: the test clock leaves a task due at exactly the time it reaches. */
    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms + 16)
        rule.waitForIdle()
    }

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
        at(start)
    }

    @After
    fun tearDown() = AppClock.setOverride(null)

    @Test
    fun `under an hour it reads minutes and seconds, from an hour hours too`() {
        assertEquals("59:59", countdownText(3599))
        assertEquals("1:00:00", countdownText(3600))
        assertEquals("1:50:00", countdownText(6600))
        assertEquals("3:00:00", countdownText(10800))
        assertEquals("0:05", countdownText(5))
        assertEquals("0:00", countdownText(0))
    }

    @Test
    fun `the widest reading of a class's countdown is its longest, every digit a 0`() {
        assertEquals("0:00:00", widestCountdown(6600))
        assertEquals("0:00:00", widestCountdown(3600))
        assertEquals("00:00", widestCountdown(3000))
        assertEquals("0:00", widestCountdown(540))
        assertEquals("00:00:00", widestCountdown(36000))
    }

    @Test
    fun `a second begun counts whole and none are left at the end`() {
        assertEquals(6600, secondsLeft(start, end))
        assertEquals(6600, secondsLeft(start + 1, end))
        assertEquals(1, secondsLeft(end - 1_000, end))
        assertEquals(0, secondsLeft(end, end))
        assertEquals(0, secondsLeft(end + 5_000, end))
    }

    @Test
    fun `the bar runs from the start to the end`() {
        assertEquals(0f, classProgress(start, start, end))
        assertEquals(0.5f, classProgress(start + 55 * 60_000L, start, end))
        assertEquals(1f, classProgress(end + 1, start, end))
    }

    @Test
    fun `each tick lands on the clock's next whole period`() {
        assertEquals(1_000, untilNextTick(start, 1_000))
        assertEquals(750, untilNextTick(start + 250, 1_000))
        assertEquals(59_000, untilNextTick(start + 1_000, 60_000))
    }

    @Test
    fun `the time left counts down each second while the watch is in use`() {
        rule.setContent { MaterialTheme { ClassCountdown(start, end, ProgressIndicatorDefaults.colors(), Color.White) } }
        rule.waitForIdle()
        rule.onNodeWithText("1:50:00").assertExists()

        at(start + 1_000)
        advance(1_000)
        rule.onNodeWithText("1:49:59").assertExists()

        at(start + 2_000)
        advance(1_000)
        rule.onNodeWithText("1:49:58").assertExists()
    }

    @Test
    fun `the countdown keeps its width as it drops under an hour`() {
        rule.setContent { MaterialTheme { ClassCountdown(start, end, ProgressIndicatorDefaults.colors(), Color.White) } }
        rule.waitForIdle()
        val longest = rule.onNodeWithTag(COUNTDOWN_TAG).fetchSemanticsNode().size.width

        at(end - 3_599_000L)
        advance(1_000)
        rule.onNodeWithText("59:59").assertExists()
        assertEquals(longest, rule.onNodeWithTag(COUNTDOWN_TAG).fetchSemanticsNode().size.width)
    }

    @Test
    fun `nothing ticks while the screen is not resumed, and a resume reads the clock at once`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
            override val lifecycle: Lifecycle get() = registry
        }
        rule.setContent { MaterialTheme { Text(appClockTicks(1_000, owner.lifecycle).toString()) } }
        rule.waitForIdle()
        rule.onNodeWithText(start.toString()).assertExists()

        at(start + 5_000)
        advance(5_000)
        rule.onNodeWithText(start.toString()).assertExists()

        owner.registry.currentState = Lifecycle.State.RESUMED
        advance(0)
        rule.onNodeWithText((start + 5_000).toString()).assertExists()
    }

    @Test
    fun `in ambient mode only the system's ambient tick reads the clock`() {
        val ambient = FakeAmbient()
        rule.setContent {
            CompositionLocalProvider(LocalAmbientModeManager provides ambient) {
                MaterialTheme { Text(appClockTicks(1_000).toString()) }
            }
        }
        rule.waitForIdle()
        at(start + 5_000)
        advance(5_000)
        rule.onNodeWithText(start.toString()).assertExists()

        ambient.tick()
        advance(0)
        rule.onNodeWithText((start + 5_000).toString()).assertExists()
    }

    private class FakeAmbient : AmbientModeManager {
        override val currentAmbientMode: AmbientMode =
            AmbientMode.Ambient(isBurnInProtectionRequired = false, isLowBitAmbientSupported = false)

        private val waiting = mutableListOf<() -> Unit>()

        override suspend fun withAmbientTick(block: () -> Unit) = suspendCancellableCoroutine<Unit> { co ->
            waiting += { co.resumeWith(runCatching(block)) }
        }

        fun tick() = waiting.toList().also { waiting.clear() }.forEach { it() }
    }
}
