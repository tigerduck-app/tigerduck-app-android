package org.ntust.app.tigerduck.ui.screen.files

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class ViewerStateTest {

    private val size = IntSize(1000, 1000)

    @Test
    fun `a gesture's end, with no finger left, leaves the zoom where it was`() {
        val zoom = ZoomState()
        zoom.change(2f, Offset(500f, 500f), Offset.Zero, size)
        val at = zoom.offset

        zoom.change(1f, Offset.Unspecified, Offset.Zero, size)
        zoom.change(1f, Offset(Float.NaN, 0f), Offset.Zero, size)
        zoom.change(1f, Offset(0f, Float.POSITIVE_INFINITY), Offset.Zero, size)

        assertEquals(2f, zoom.scale)
        assertEquals(at, zoom.offset)
        assertTrue(zoom.offset.x.isFinite() && zoom.offset.y.isFinite())
    }

    @Test
    fun `a pinch that moves keeps what was under the fingers under them`() {
        val zoom = ZoomState()
        // The fingers were at 100, 100, spread to twice as far apart and moved 10 to the right.
        zoom.change(2f, centroid = Offset(100f, 100f), pan = Offset(-10f, 0f), size = size)

        // The point that was under them is drawn at 110, 100.
        val point = Offset(100f, 100f)
        assertEquals(Offset(110f, 100f), point * zoom.scale - zoom.offset)
    }

    private class Resource : Closeable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test
    fun `a document opened after the screen left is closed at once`() {
        val owner = Owner<Resource>()
        owner.close()

        val late = Resource()
        assertFalse(owner.adopt(late))

        assertEquals(1, late.closed)
    }

    @Test
    fun `a document the screen holds is closed once when it leaves`() {
        val owner = Owner<Resource>()
        val document = Resource()
        assertTrue(owner.adopt(document))
        assertEquals(document, owner.value)

        owner.close()
        owner.close()

        assertEquals(1, document.closed)
    }
}
