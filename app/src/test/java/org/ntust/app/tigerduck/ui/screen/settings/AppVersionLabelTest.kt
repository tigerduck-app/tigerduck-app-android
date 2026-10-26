package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionLabelTest {
    @Test
    fun `the version row carries the build number, which tells test builds apart in a bug report`() {
        // The iPhone shows "2.4.0 (Build 123)"; the number alone needs no translation.
        assertEquals("2.3.1 (29)", appVersionLabel("2.3.1", 29))
        assertEquals("2.3.1-fdroid (29)", appVersionLabel("2.3.1-fdroid", 29))
    }
}
