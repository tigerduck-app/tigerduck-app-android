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
            hasSyncContentLeft(false, false, false, false, syncAssignmentReminders = false, syncLiveActivity = false),
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
                syncAssignmentReminders = false,
                syncLiveActivity = true,
            ),
        )
    }

    /**
     * `syncAssignmentReminders` now carries this device's assignment-reminder
     * `enabled`/offsets through the shared `notification` document
     * (`NotificationSettingsSync`), which needs TigerSync on — the same
     * reason `syncLiveActivity` alone already kept it on above. Before that
     * sync existed, this switch gave TigerSync nothing to do and was
     * deliberately excluded; the assertion below is the opposite of what
     * that earlier, now-incorrect version of this function would return.
     */
    @Test
    fun `assignment-reminder sync alone keeps TigerSync on`() {
        assertTrue(
            "a user who keeps only 作業到期提醒 sync must be able to keep TigerSync on for it",
            hasSyncContentLeft(
                syncCourses = false,
                syncCourseColors = false,
                syncCourseNames = false,
                syncAssignments = false,
                syncAssignmentReminders = true,
                syncLiveActivity = false,
            ),
        )
    }

    @Test
    fun `each course and assignment switch alone still keeps TigerSync on`() {
        assertTrue("syncCourses", hasSyncContentLeft(true, false, false, false, false, false))
        assertTrue("syncCourseColors", hasSyncContentLeft(false, true, false, false, false, false))
        assertTrue("syncCourseNames", hasSyncContentLeft(false, false, true, false, false, false))
        assertTrue("syncAssignments", hasSyncContentLeft(false, false, false, true, false, false))
    }
}
