package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.push.SyncChange
import org.ntust.app.tigerduck.push.SyncChangesPage
import org.ntust.app.tigerduck.ui.screen.home.ChangeFeedPlan.Advance
import org.ntust.app.tigerduck.ui.screen.home.ChangeFeedPlan.FullSync

/**
 * What a revision poll makes of the change feed ([ChangeFeedPlan]), as the
 * iPhone's (tigerduck-app-android#120): a revision move ran a full sync, and
 * the server's own Moodle syncs move it every few minutes.
 */
class ChangeFeedPlanTest {

    private fun page(
        vararg changes: Pair<Long, String>,
        current: Long = 7,
        until: Long = current,
        hasMore: Boolean = false,
    ) = SyncChangesPage(current, until, hasMore, changes.map { (revision, type) -> SyncChange(revision, type) })

    @Test
    fun `assignment and bulletin changes alone only move the watermark`() {
        assertEquals(
            Advance(7),
            ChangeFeedPlan.of(
                page(4L to "assignment", 5L to "bulletin_subscription", 6L to "bulletin_match", 7L to "bulletin_state"),
                since = 3,
            ),
        )
    }

    @Test
    fun `any other change takes the full sync`() {
        for (type in listOf("assignment_override", "course", "course_override", "course_skipped_date", "holiday_override", "settings_document", "")) {
            assertEquals(type, FullSync, ChangeFeedPlan.of(page(6L to "assignment", 7L to type), since = 5))
        }
    }

    @Test
    fun `a page that may not hold every change up to the current revision takes the full sync`() {
        assertEquals("more to come", FullSync, ChangeFeedPlan.of(page(7L to "assignment", hasMore = true), since = 5))
        assertEquals("stops short of the current revision", FullSync, ChangeFeedPlan.of(page(6L to "assignment", until = 6), since = 5))
        assertEquals("its last change is not the current revision", FullSync, ChangeFeedPlan.of(page(6L to "assignment"), since = 5))
        assertEquals("no changes", FullSync, ChangeFeedPlan.of(page(), since = 5))
        assertEquals("a change at or before the watermark", FullSync, ChangeFeedPlan.of(page(5L to "assignment", 7L to "assignment"), since = 5))
        assertEquals("no page", FullSync, ChangeFeedPlan.of(null, since = 5))
    }
}
