package org.ntust.app.tigerduck.ui.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticsTest {

    @Test
    fun `tunable filter excludes library warning`() {
        val tunable = HapticScenario.tunable
        assertEquals(5, tunable.size)
        assert(HapticScenario.LibraryWarning !in tunable)
    }

    @Test
    fun `every scenario has positive defaults`() {
        HapticScenario.entries.forEach { scenario ->
            assert(scenario.defaultStrengthPct in 1..100) {
                "${scenario.name} has invalid default strength ${scenario.defaultStrengthPct}"
            }
            assert(scenario.defaultDurationMs in 1..2000) {
                "${scenario.name} has invalid default duration ${scenario.defaultDurationMs}"
            }
        }
    }

    @Test
    fun `tick threshold matches contract`() {
        // Documents the cutoff used to choose PRIMITIVE_TICK vs PRIMITIVE_CLICK.
        assertEquals(10, Haptics.TICK_THRESHOLD_MS)
    }
}
