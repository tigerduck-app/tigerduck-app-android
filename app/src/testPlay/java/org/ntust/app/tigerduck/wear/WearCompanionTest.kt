package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertTrue
import org.junit.Test

/** The play flavor ships the Wear OS app, so Settings offers the watch's gradient switch. */
class WearCompanionTest {

    @Test
    fun `this flavor ships the watch app`() {
        assertTrue(WearCompanion.SHIPS)
    }
}
