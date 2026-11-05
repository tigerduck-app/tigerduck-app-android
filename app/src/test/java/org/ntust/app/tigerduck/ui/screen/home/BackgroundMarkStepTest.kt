package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.AssignmentMarks
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The background worker's mark step ([BackgroundMarkStep]): it takes the server's marks only while
 * the Assignments switch stays on, decided in the write's own step (Greptile on #306).
 */
class BackgroundMarkStepTest {

    private var switchOn = true
    private var pending = emptySet<String>()
    private var ignored = emptySet<String>()
    private var completed = emptySet<String>()
    private var writes = 0
    private var unsent = emptyMap<String, String>()

    /** Runs as the store reads the waiting marks, for a change made meanwhile. */
    private var onUnsentRead: () -> Unit = {}

    private val holds = PullHolds(1L, AckClock())

    private val step = BackgroundMarkStep(
        switchOn = { switchOn },
        pendingConflicts = { pending },
        // As DataCache.updateAssignmentMarks: the marks and those waiting read in one step, written only when changed.
        store = AssignmentMarkStep.MarkStore { _, change ->
            val waiting = unsent.map { (id, status) -> UnsentMark(id, status) }.also { onUnsentRead() }
            val stored = AssignmentMarks(ignored, completed)
            change(stored, waiting).also {
                if (it != stored) writes++
                ignored = it.ignored
                completed = it.completed
            }
        },
    )

    private fun server(ignored: Set<String> = emptySet()) = BackendSyncResult(
        ignoredIds = ignored,
        completedIds = emptySet(),
        courseOverrides = emptyList(),
        serverCourseNos = emptySet(),
        currentRevision = 1,
    )

    @Test
    fun `the server's marks are taken`() = runTest {
        val outcome = step.pull(server(ignored = setOf("1")), testSignIn(), holds, syncedBefore = true)

        assertTrue(outcome.taken)
        assertTrue(outcome.changed)
        assertEquals(setOf("1"), ignored)
    }

    @Test
    fun `a switch turned off while the waiting marks were read leaves this phone's marks alone`() = runTest {
        completed = setOf("2")
        onUnsentRead = { switchOn = false }

        val outcome = step.pull(server(ignored = setOf("1")), testSignIn(), holds, syncedBefore = true)

        assertFalse(outcome.taken)
        assertEquals(0, writes)
        assertEquals(setOf("2"), completed)
    }

    @Test
    fun `with the switch off, the check pending or a first sync nothing is written`() = runTest {
        switchOn = false
        assertFalse(step.pull(server(ignored = setOf("1")), testSignIn(), holds, syncedBefore = true).taken)
        switchOn = true
        pending = setOf("assignments")
        assertFalse(step.pull(server(ignored = setOf("1")), testSignIn(), holds, syncedBefore = true).taken)
        pending = emptySet()
        ignored = setOf("9")
        assertFalse(step.pull(server(), testSignIn(), holds, syncedBefore = false).taken)

        assertEquals(0, writes)
    }
}
