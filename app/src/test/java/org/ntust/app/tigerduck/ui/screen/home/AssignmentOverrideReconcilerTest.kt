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

    private fun reconcile(
        serverIgnored: Set<String> = emptySet(),
        serverCompleted: Set<String> = emptySet(),
        localIgnored: Set<String> = emptySet(),
        localCompleted: Set<String> = emptySet(),
        inFlightIgnored: Set<String> = emptySet(),
        inFlightCompleted: Set<String> = emptySet(),
        pendingOverrides: Set<String> = emptySet(),
        labelFor: (String) -> String? = { null },
    ) = AssignmentOverrideReconciler.reconcile(
        serverIgnored = serverIgnored,
        serverCompleted = serverCompleted,
        localIgnored = localIgnored,
        localCompleted = localCompleted,
        inFlightIgnored = inFlightIgnored,
        inFlightCompleted = inFlightCompleted,
        pendingOverrides = pendingOverrides,
        labelFor = labelFor,
    )

    // --- first upload ------------------------------------------------------

    @Test
    fun `first upload when server is empty and this device has overrides`() {
        assertTrue(
            AssignmentOverrideReconciler.isFirstUpload(
                syncedBefore = false,
                serverIgnored = emptySet(),
                serverCompleted = emptySet(),
                localIgnored = setOf("1"),
                localCompleted = emptySet(),
            )
        )
    }

    @Test
    fun `not a first upload once the server knows anything`() {
        assertFalse(
            AssignmentOverrideReconciler.isFirstUpload(
                syncedBefore = false,
                serverIgnored = setOf("9"),
                serverCompleted = emptySet(),
                localIgnored = setOf("1"),
                localCompleted = emptySet(),
            )
        )
    }

    @Test
    fun `not a first upload once a sync has taken the account's marks here`() {
        // The server is empty because the last mark was cleared on another
        // device; sending this device's older copy up would bring it back.
        assertFalse(
            AssignmentOverrideReconciler.isFirstUpload(
                syncedBefore = true,
                serverIgnored = emptySet(),
                serverCompleted = emptySet(),
                localIgnored = setOf("1"),
                localCompleted = setOf("2"),
            )
        )
    }

    @Test
    fun `not a first upload when neither side has anything`() {
        // Both empty is a normal steady state, not a migration — treating it
        // as one would fire a pointless PATCH storm on every sync.
        assertFalse(
            AssignmentOverrideReconciler.isFirstUpload(
                syncedBefore = false,
                serverIgnored = emptySet(),
                serverCompleted = emptySet(),
                localIgnored = emptySet(),
                localCompleted = emptySet(),
            )
        )
    }

    // --- the happy paths ---------------------------------------------------

    @Test
    fun `server override propagates to a device that has none`() {
        val out = reconcile(serverIgnored = setOf("1"))
        assertEquals(setOf("1"), out.ignored)
        assertTrue(out.conflicts.isEmpty())
    }

    @Test
    fun `agreement is not a conflict`() {
        val out = reconcile(serverIgnored = setOf("1"), localIgnored = setOf("1"))
        assertEquals(setOf("1"), out.ignored)
        assertTrue(out.conflicts.isEmpty())
    }

    @Test
    fun `local-only override is dropped when the server does not have it`() {
        // The server is authoritative for ids it has an opinion on, and
        // "absent" is an opinion once we are past first upload — this is how
        // un-ignoring on another device propagates here.
        val out = reconcile(localIgnored = setOf("1"))
        assertTrue(out.ignored.isEmpty())
        assertTrue(out.conflicts.isEmpty())
    }

    // --- genuine disagreement ---------------------------------------------

    @Test
    fun `ignored here and completed there is a conflict, and local wins for now`() {
        val out = reconcile(
            serverCompleted = setOf("1"),
            localIgnored = setOf("1"),
            labelFor = { "Essay 1" },
        )
        assertEquals(1, out.conflicts.size)
        out.conflicts.single().let {
            assertEquals("1", it.id)
            assertEquals("Essay 1", it.label)
            assertEquals(STATUS_IGNORED, it.localStatus)
            assertEquals(STATUS_COMPLETED, it.serverStatus)
        }
        // Contested id keeps its local value until the user answers.
        assertEquals(setOf("1"), out.ignored)
        assertTrue(out.completed.isEmpty())
    }

    @Test
    fun `an id the device has never seen still gets a usable label`() {
        val out = reconcile(
            serverCompleted = setOf("42"),
            localIgnored = setOf("42"),
            labelFor = { null },
        )
        assertEquals("ID 42", out.conflicts.single().label)
    }

    @Test
    fun `non-conflicting ids are still applied alongside a conflict`() {
        val out = reconcile(
            serverIgnored = setOf("2"),
            serverCompleted = setOf("1"),
            localIgnored = setOf("1"),
        )
        assertEquals(1, out.conflicts.size)
        // "2" has no local opinion, so it applies immediately — an unresolved
        // dialog must not freeze everything else about the sync.
        assertEquals(setOf("1", "2"), out.ignored)
    }

    // --- in-flight taps ----------------------------------------------------

    @Test
    fun `an in-flight tap is not reverted by stale server state`() {
        // User just tapped 已忽略 on id 1; the PATCH has not landed, so the
        // server still says nothing. Without the carve-out the tap vanishes.
        val out = reconcile(
            serverIgnored = emptySet(),
            localIgnored = emptySet(),
            inFlightIgnored = setOf("1"),
            pendingOverrides = setOf("1"),
        )
        assertEquals(setOf("1"), out.ignored)
        assertTrue(out.conflicts.isEmpty())
    }

    @Test
    fun `an in-flight tap never raises a conflict dialog`() {
        val out = reconcile(
            serverCompleted = setOf("1"),
            localIgnored = setOf("1"),
            inFlightIgnored = setOf("1"),
            pendingOverrides = setOf("1"),
        )
        assertTrue(out.conflicts.isEmpty())
        assertEquals(setOf("1"), out.ignored)
    }

    @Test
    fun `an in-flight un-toggle is not resurrected by stale server state`() {
        // Mirror image of the test above: the user just *cleared* an override
        // and the PATCH has not landed, so the server still reports it. Fixed
        // by subtracting pendingOverrides from the server sets before merging
        // — adding the in-flight values alone left the server's copy in place
        // and the row the user un-ignored vanished again.
        val out = reconcile(
            serverIgnored = setOf("1"),
            localIgnored = setOf("1"),
            inFlightIgnored = emptySet(),
            pendingOverrides = setOf("1"),
        )
        assertTrue(out.ignored.isEmpty())
    }

    @Test
    fun `an in-flight switch from ignored to completed lands on completed only`() {
        // Both sets carry the id at once during the swap; the server still
        // says ignored. Neither half of the carve-out may leak the old value.
        val out = reconcile(
            serverIgnored = setOf("1"),
            localIgnored = setOf("1"),
            inFlightIgnored = emptySet(),
            inFlightCompleted = setOf("1"),
            pendingOverrides = setOf("1"),
        )
        assertTrue(out.ignored.isEmpty())
        assertEquals(setOf("1"), out.completed)
    }

    @Test
    fun `the carve-out is scoped to pending ids only`() {
        // An id that is not in flight must still take the server's answer,
        // otherwise subtracting would quietly disable sync altogether.
        val out = reconcile(
            serverIgnored = setOf("1", "2"),
            inFlightIgnored = emptySet(),
            pendingOverrides = setOf("1"),
        )
        assertEquals(setOf("2"), out.ignored)
    }

    // --- resolving in the server's favour ----------------------------------

    @Test
    fun `serverWins takes server state but still honours in-flight taps`() {
        val out = AssignmentOverrideReconciler.serverWins(
            serverIgnored = setOf("1"),
            serverCompleted = setOf("2"),
            inFlightIgnored = setOf("3"),
            inFlightCompleted = setOf("4"),
            pendingOverrides = setOf("3"),
        )
        assertEquals(setOf("1", "3"), out.ignored)
        // "4" is not pending, so it is not carried over.
        assertEquals(setOf("2"), out.completed)
        assertTrue(out.conflicts.isEmpty())
    }

    @Test
    fun `serverWins does not resurrect an override cleared while the dialog was open`() {
        // The tap happened after the user chose "keep the server's version",
        // so it is the more recent intent and wins over it.
        val out = AssignmentOverrideReconciler.serverWins(
            serverIgnored = setOf("1"),
            serverCompleted = emptySet(),
            inFlightIgnored = emptySet(),
            inFlightCompleted = emptySet(),
            pendingOverrides = setOf("1"),
        )
        assertTrue(out.ignored.isEmpty())
    }

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
            AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks = false, pendingConflictCategories = emptySet()),
        )
    }

    @Test
    fun `the assignments re-enable check leaves the marks alone too`() {
        assertEquals(
            "re-enable check pending",
            AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks = true, pendingConflictCategories = setOf("assignments")),
        )
    }

    @Test
    fun `with the switch on and no check pending a full sync syncs the marks`() {
        assertEquals(
            null,
            AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks = true, pendingConflictCategories = setOf("courses")),
        )
    }

    // --- the background worker's mark step ---

    private fun backgroundTakes(
        syncsMarks: Boolean = true,
        pending: Set<String> = emptySet(),
        syncedBefore: Boolean = true,
        serverIgnored: Set<String> = emptySet(),
        localIgnored: Set<String> = emptySet(),
    ) = AssignmentOverrideReconciler.backgroundTakesMarks(
        syncsMarks = syncsMarks,
        pendingConflictCategories = pending,
        syncedBefore = syncedBefore,
        serverIgnored = serverIgnored,
        serverCompleted = emptySet(),
        localIgnored = localIgnored,
        localCompleted = emptySet(),
    )

    @Test
    fun `the background sync takes the server's marks`() {
        assertTrue(backgroundTakes(serverIgnored = setOf("1")))
    }

    @Test
    fun `the background sync leaves the marks alone with the Assignments switch off`() {
        assertFalse(backgroundTakes(syncsMarks = false, serverIgnored = setOf("1")))
    }

    @Test
    fun `the background sync leaves the marks alone while the re-enable check is pending`() {
        assertFalse(backgroundTakes(pending = setOf("assignments"), serverIgnored = setOf("1")))
    }

    @Test
    fun `the background sync leaves a first sync's marks for Home to send up`() {
        assertFalse(backgroundTakes(syncedBefore = false, localIgnored = setOf("1")))
    }

    @Test
    fun `after the first sync the background sync takes an empty server too`() {
        assertTrue(backgroundTakes(syncedBefore = true, localIgnored = setOf("1")))
    }

    @Test
    fun `the background sync keeps a tap whose PATCH has not landed`() {
        val out = AssignmentOverrideReconciler.backgroundMarks(
            serverIgnored = emptySet(),
            serverCompleted = setOf("2"),
            unsent = mapOf("3" to STATUS_COMPLETED),
        )

        assertEquals(setOf("2", "3"), out.completed)
        assertEquals(emptySet<String>(), out.ignored)
    }

    @Test
    fun `the background sync keeps an unsent clear too`() {
        val out = AssignmentOverrideReconciler.backgroundMarks(
            serverIgnored = setOf("1"),
            serverCompleted = emptySet(),
            unsent = mapOf("1" to STATUS_NONE),
        )

        assertEquals(emptySet<String>(), out.ignored)
    }

    @Test
    fun `the background sync takes a waiting mark's status from the mark, not from an older read`() {
        // Marked done on Home after the worker read this phone's marks: the
        // waiting mark is the newer statement (Greptile on #306).
        val out = AssignmentOverrideReconciler.backgroundMarks(
            serverIgnored = setOf("3"),
            serverCompleted = emptySet(),
            unsent = mapOf("3" to STATUS_COMPLETED),
        )

        assertEquals(setOf("3"), out.completed)
        assertEquals(emptySet<String>(), out.ignored)
    }
}
