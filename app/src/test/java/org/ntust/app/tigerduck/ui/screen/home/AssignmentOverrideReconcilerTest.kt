package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED

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
                serverIgnored = setOf("9"),
                serverCompleted = emptySet(),
                localIgnored = setOf("1"),
                localCompleted = emptySet(),
            )
        )
    }

    @Test
    fun `not a first upload when neither side has anything`() {
        // Both empty is a normal steady state, not a migration — treating it
        // as one would fire a pointless PATCH storm on every sync.
        assertFalse(
            AssignmentOverrideReconciler.isFirstUpload(
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
    fun `KNOWN GAP - an in-flight un-toggle is resurrected by stale server state`() {
        // Mirror image of the test above: the user just *cleared* an
        // override and the PATCH has not landed, so the server still reports
        // it. The in-flight carve-out does not protect this direction.
        //
        // The merge starts from the server's set and pendingOverrides only
        // ever *adds* to it, so an id the server still holds is re-applied
        // even though we know our view of it is stale. In the app that means
        // the row the user just un-ignored disappears again, and the wrong
        // state is written to disk, until the next sync after the PATCH
        // lands corrects it.
        //
        // Asserted as-is rather than fixed: this is the behaviour that has
        // shipped, and making the carve-out authoritative in both directions
        // is a change to conflict semantics, not a refactor. The fix would be
        // to drop pending ids from the server sets before merging, then add
        // the in-flight values back.
        val out = reconcile(
            serverIgnored = setOf("1"),
            localIgnored = setOf("1"),
            inFlightIgnored = emptySet(),
            pendingOverrides = setOf("1"),
        )
        assertEquals(setOf("1"), out.ignored)
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
}
