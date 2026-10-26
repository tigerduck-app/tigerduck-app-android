package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner's flashlight: offered only with a flash, showing the real torch, and off once the camera stops. */
class ScannerTorchTest {

    private val sent = mutableListOf<Boolean>()
    private val torch = ScannerTorch()

    @Test
    fun `nothing is offered before a camera is bound, or for one without a flash`() {
        assertFalse(torch.isAvailable)
        torch.toggle()

        torch.bound(enable = null)
        assertFalse(torch.isAvailable)
        torch.toggle()
        torch.stateChanged(on = true)
        assertFalse(torch.isOn)
        assertEquals(emptyList<Boolean>(), sent)
    }

    @Test
    fun `the toggle asks the camera, and the icon shows what the camera reports`() {
        torch.bound(enable = sent::add)
        assertTrue(torch.isAvailable)
        assertFalse(torch.isOn)

        torch.toggle()
        assertEquals(listOf(true), sent)
        // Not on until the camera says so.
        assertFalse(torch.isOn)
        torch.stateChanged(on = true)
        assertTrue(torch.isOn)

        // The system turned it off, as when the battery runs low: the next tap turns it on again.
        torch.stateChanged(on = false)
        assertFalse(torch.isOn)
        torch.toggle()
        assertEquals(listOf(true, true), sent)

        torch.stateChanged(on = true)
        torch.toggle()
        assertEquals(listOf(true, true, false), sent)
    }

    @Test
    fun `the torch goes off with the camera, and a camera bound again starts with it off`() {
        torch.bound(enable = sent::add)
        torch.toggle()
        torch.stateChanged(on = true)

        torch.unbinding()
        assertEquals(listOf(true, false), sent)
        assertFalse(torch.isAvailable)
        assertFalse(torch.isOn)
        torch.toggle()
        torch.stateChanged(on = true)
        assertFalse(torch.isOn)
        assertEquals(listOf(true, false), sent)

        torch.bound(enable = sent::add)
        assertFalse(torch.isOn)
    }

    @Test
    fun `a camera stopping with the torch off sends nothing`() {
        torch.bound(enable = sent::add)
        torch.unbinding()
        assertEquals(emptyList<Boolean>(), sent)
    }

    @Test
    fun `the toggle's place is held until the camera answers, and let go when there is none`() {
        assertTrue(torch.isPending)
        torch.bound(enable = sent::add)
        assertFalse(torch.isPending)

        val none = ScannerTorch()
        none.unavailable()
        assertFalse(none.isPending)
        assertFalse(none.isAvailable)

        val noFlash = ScannerTorch()
        noFlash.bound(enable = null)
        assertFalse(noFlash.isPending)
        assertFalse(noFlash.isAvailable)
    }
}
