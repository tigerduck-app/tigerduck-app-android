package org.ntust.app.tigerduck.ui.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {

    @Test
    fun `every scenario is tunable`() {
        val tunable = HapticScenario.tunable
        assertEquals(HapticScenario.entries, tunable)
        // Goes through the same per-scenario sliders as every other haptic,
        // so muting them in Settings → Vibration silences the copy too.
        assertTrue(HapticScenario.CourseCodeCopy in tunable)
    }

    @Test
    fun `every scenario has positive defaults`() {
        HapticScenario.entries.forEach { scenario ->
            assertTrue(
                "${scenario.name} has invalid default strength ${scenario.defaultStrengthPct}",
                scenario.defaultStrengthPct in 1..100,
            )
            assertTrue(
                "${scenario.name} has invalid default duration ${scenario.defaultDurationMs}",
                scenario.defaultDurationMs in 1..2000,
            )
        }
    }

    @Test
    fun `tick threshold matches contract`() {
        // Documents the cutoff used to choose PRIMITIVE_TICK vs PRIMITIVE_CLICK.
        assertEquals(10, Haptics.TICK_THRESHOLD_MS)
    }

    @Test
    fun `tunable scenarios do not force one-shot`() {
        HapticScenario.tunable.forEach { scenario ->
            assertEquals(false, scenario.forceOneShot)
        }
    }
}
