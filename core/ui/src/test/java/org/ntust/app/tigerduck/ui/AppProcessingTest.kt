package org.ntust.app.tigerduck.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Holds on the app-wide spinner: each counts once, from its start to its stop. */
class AppProcessingTest {

    private val processing = AppProcessing()

    @Test
    fun `a hold counts from its start to its stop, once however often either is called`() {
        val hold = processing.newHold()
        assertEquals(0, processing.holds.value)

        hold.start()
        hold.start()
        assertEquals(1, processing.holds.value)

        hold.stop()
        hold.stop()
        assertEquals(0, processing.holds.value)
    }

    @Test
    fun `two holds count apart, and the spinner stays until both stop`() {
        val first = processing.newHold()
        val second = processing.newHold()

        first.start()
        second.start()
        assertEquals(2, processing.holds.value)
        first.stop()
        assertEquals(1, processing.holds.value)
        second.stop()
        assertEquals(0, processing.holds.value)
    }

    @Test
    fun `a hold stopped before it starts counts nothing`() {
        val hold = processing.newHold()

        hold.stop()
        assertEquals(0, processing.holds.value)
        hold.start()
        assertEquals(1, processing.holds.value)
    }
}
