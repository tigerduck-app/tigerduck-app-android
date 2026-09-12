package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [hasSyncContentLeft], the rule behind the TigerSync screen switching
 * TigerSync off by itself once nothing under 同步內容 is left for it to sync.
 */
class CloudSyncAutoOffTest {

    @Test
    fun `with every sync content switch off, TigerSync has nothing left to do`() {
        assertFalse(
            "with nothing left to sync, TigerSync must switch itself off",
            hasSyncContentLeft(false, false, false, false, syncLiveActivity = false),
        )
    }

    @Test
    fun `live-activity sync alone keeps TigerSync on`() {
        assertTrue(
            "a user who keeps only 即時更新 sync must be able to keep TigerSync on for it",
            hasSyncContentLeft(
                syncCourses = false,
                syncCourseColors = false,
                syncCourseNames = false,
                syncAssignments = false,
                syncLiveActivity = true,
            ),
        )
    }

    @Test
    fun `each course and assignment switch alone still keeps TigerSync on`() {
        assertTrue("syncCourses", hasSyncContentLeft(true, false, false, false, false))
        assertTrue("syncCourseColors", hasSyncContentLeft(false, true, false, false, false))
        assertTrue("syncCourseNames", hasSyncContentLeft(false, false, true, false, false))
        assertTrue("syncAssignments", hasSyncContentLeft(false, false, false, true, false))
    }
}
