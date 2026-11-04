package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_NONE

/**
 * These pin the rules that decide whether a user's 已忽略 / 標示為完成 tap
 * survives a sync. Every one of them is a way a tap could silently disappear
 * on a two-device account.
 */
class AssignmentOverrideReconcilerTest {

    // --- keeping this device's overrides after sync is turned back on ------

    @Test
    fun `keepLocal clears an override only the server has`() {
        // Left on the server, "3" and "4" read as another device's marks at
        // the next full sync and come back here.
        val sent = AssignmentOverrideReconciler.keepLocal(
            localIgnored = setOf("1"),
            localCompleted = setOf("2"),
            serverIgnored = setOf("1", "3"),
            serverCompleted = setOf("4"),
        )
        assertEquals(
            mapOf(
                "1" to STATUS_IGNORED,
                "2" to STATUS_COMPLETED,
                "3" to STATUS_NONE,
                "4" to STATUS_NONE,
            ),
            sent,
        )
    }

    @Test
    fun `keepLocal sends this device's status where the server has another`() {
        val sent = AssignmentOverrideReconciler.keepLocal(
            localIgnored = setOf("1"),
            localCompleted = emptySet(),
            serverIgnored = emptySet(),
            serverCompleted = setOf("1"),
        )
        assertEquals(mapOf("1" to STATUS_IGNORED), sent)
    }

    @Test
    fun `keepLocal without the server's overrides sends only this device's`() {
        val sent = AssignmentOverrideReconciler.keepLocal(
            localIgnored = setOf("1"),
            localCompleted = setOf("2"),
            serverIgnored = emptySet(),
            serverCompleted = emptySet(),
        )
        assertEquals(mapOf("1" to STATUS_IGNORED, "2" to STATUS_COMPLETED), sent)
    }

    @Test
    fun `overrides are held while the assignments re-enable conflict waits for the user`() {
        assertTrue(AssignmentOverrideReconciler.isHeldForReenable(setOf("assignments")))
        assertTrue(AssignmentOverrideReconciler.isHeldForReenable(setOf("courses", "assignments")))
    }

    @Test
    fun `overrides are not held for another category's re-enable conflict`() {
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(emptySet()))
        assertFalse(
            AssignmentOverrideReconciler.isHeldForReenable(setOf("courses", "course_colors", "course_names"))
        )
    }

    // --- the Assignments sync switch (tigerduck-app-android#188, #209) ---

    @Test
    fun `with the Assignments switch off a full sync leaves this device's marks alone`() {
        // Neither taking the server's marks nor sending this device's up as a
        // first sync: the switch keeps the two apart, as on iOS.
        assertEquals(
            "Assignments sync is off",
            AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks = false, held = emptySet()),
        )
    }

    @Test
    fun `the assignments re-enable check leaves the marks alone too`() {
        assertEquals(
            "re-enable check pending",
            AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks = true, held = setOf("assignments")),
        )
    }

    @Test
    fun `with the switch on and no check pending a full sync syncs the marks`() {
        assertEquals(
            null,
            AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks = true, held = setOf("courses")),
        )
    }
}
