package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearingStepsTest {
    private val tried = mutableListOf<String>()

    private fun failing(name: String): () -> Unit = {
        tried += name
        throw IllegalStateException("made-up failure")
    }

    @Test
    fun `every step is tried, whatever the ones before did`() {
        val steps = ClearingSteps()

        steps.once("first", failing("first"))
        steps.must("second", failing("second"))
        steps.must("third") { tried += "third" }
        steps.once("fourth") { tried += "fourth" }

        assertEquals(listOf("first", "second", "third", "fourth"), tried)
    }

    @Test
    fun `a must step that fails leaves the clearing not done`() {
        val steps = ClearingSteps()

        steps.must("failing", failing("failing"))
        steps.must("going") {}

        assertFalse(steps.cleared)
    }

    @Test
    fun `a once step that fails leaves it done`() {
        val steps = ClearingSteps()

        steps.once("failing", failing("failing"))

        assertTrue(steps.cleared)
    }

    @Test
    fun `an Error is a failure too, as it would otherwise stop every start`() {
        val steps = ClearingSteps()

        steps.must("failing") { throw StackOverflowError() }

        assertFalse(steps.cleared)
    }
}
