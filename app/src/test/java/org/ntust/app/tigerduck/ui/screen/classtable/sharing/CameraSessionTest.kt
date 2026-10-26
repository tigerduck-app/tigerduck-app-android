package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The import page's camera binds only while the page is there, and lets go when it leaves. */
class CameraSessionTest {

    private val events = mutableListOf<String>()
    private val bind: () -> (() -> Unit) = {
        events += "bind"
        { events += "unbind" }
    }

    @Test
    fun `a camera ready after the page left binds nothing`() {
        val session = CameraSession()

        session.dispose()
        val bound = session.start(bind)

        assertFalse(bound)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a camera bound while the page is there lets go when it leaves, once`() {
        val session = CameraSession()

        assertTrue(session.start(bind))
        session.dispose()
        session.dispose()

        assertEquals(listOf("bind", "unbind"), events)
    }

    @Test
    fun `a page that leaves before anything was bound unbinds nothing`() {
        val session = CameraSession()

        session.dispose()

        assertEquals(emptyList<String>(), events)
    }
}
