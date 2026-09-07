package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Grey never wins" is the whole reason this is a worst-of and not a
 * first-of: three sources sit behind one mark now, and a backend nobody has
 * contacted yet must not be able to report the page as healthy — or, when
 * switched off, as broken.
 */
class SyncStatusDotTest {

    @Test
    fun `a single failure decides the dot, whatever else is fine`() {
        assertEquals(
            ServerStatus.FAILED,
            summarize(listOf(ServerStatus.OK, ServerStatus.FAILED, ServerStatus.OK)),
        )
    }

    @Test
    fun `unknown never outranks a source that actually answered`() {
        assertEquals(
            ServerStatus.OK,
            summarize(listOf(ServerStatus.UNKNOWN, ServerStatus.OK)),
        )
        assertEquals(
            ServerStatus.FAILED,
            summarize(listOf(ServerStatus.UNKNOWN, ServerStatus.FAILED)),
        )
    }

    @Test
    fun `unknown wins only when nothing else is known`() {
        assertEquals(ServerStatus.UNKNOWN, summarize(listOf(ServerStatus.UNKNOWN)))
        assertEquals(ServerStatus.UNKNOWN, summarize(emptyList()))
    }
}
