package org.ntust.app.tigerduck.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdatePromptGateTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `does not prompt when update is not stale enough`() {
        assertFalse(
            UpdatePromptGate.shouldStartFlow(
                stalenessDays = 2,
                availableVersionCode = 21,
                lastPromptVersionCode = -1,
                lastPromptEpoch = 0L,
                now = now,
            )
        )
    }

    @Test
    fun `does not prompt when staleness is unknown`() {
        assertFalse(
            UpdatePromptGate.shouldStartFlow(
                stalenessDays = null,
                availableVersionCode = 21,
                lastPromptVersionCode = -1,
                lastPromptEpoch = 0L,
                now = now,
            )
        )
    }

    @Test
    fun `prompts when staleness meets the threshold and no prior prompt`() {
        assertTrue(
            UpdatePromptGate.shouldStartFlow(
                stalenessDays = 3,
                availableVersionCode = 21,
                lastPromptVersionCode = -1,
                lastPromptEpoch = 0L,
                now = now,
            )
        )
    }

    @Test
    fun `does not prompt within cooldown for the same version`() {
        assertFalse(
            UpdatePromptGate.shouldStartFlow(
                stalenessDays = 10,
                availableVersionCode = 21,
                lastPromptVersionCode = 21,
                lastPromptEpoch = now - TimeUnit.DAYS.toMillis(6),
                now = now,
            )
        )
    }

    @Test
    fun `prompts again after cooldown elapses for the same version`() {
        assertTrue(
            UpdatePromptGate.shouldStartFlow(
                stalenessDays = 10,
                availableVersionCode = 21,
                lastPromptVersionCode = 21,
                lastPromptEpoch = now - TimeUnit.DAYS.toMillis(8),
                now = now,
            )
        )
    }

    @Test
    fun `prompts immediately for a newer version even within cooldown window`() {
        assertTrue(
            UpdatePromptGate.shouldStartFlow(
                stalenessDays = 10,
                availableVersionCode = 22,
                lastPromptVersionCode = 21,
                lastPromptEpoch = now - TimeUnit.DAYS.toMillis(1),
                now = now,
            )
        )
    }
}
