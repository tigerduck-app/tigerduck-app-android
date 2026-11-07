package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertFalse
import org.junit.Test

/** F-Droid's build has no watch app, so Settings offers no switch for one. */
class WearCompanionTest {

    @Test
    fun `this flavor ships no watch app`() {
        assertFalse(WearCompanion.SHIPS)
    }
}
