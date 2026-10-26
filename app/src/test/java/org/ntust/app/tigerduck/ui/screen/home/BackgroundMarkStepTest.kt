package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED

/**
 * The background worker's mark step ([BackgroundMarkStep]): it takes the
 * server's marks only while the Assignments switch stays on and no tap has
 * changed this phone's marks since it read them (Greptile on #306).
 */
class BackgroundMarkStepTest {

    private var switchOn = true
    private var pending = emptySet<String>()
    private var syncedBefore = true
    private var ignored = emptySet<String>()
    private var completed = emptySet<String>()
    private var version = 0
    private var writes = 0
    private var unsent = emptyMap<String, String>()

    /** Runs as the step reads the waiting marks, for a change made meanwhile. */
    private var onUnsentRead: () -> Unit = {}

    private val step = BackgroundMarkStep(
        switchOn = { switchOn },
        pendingConflicts = { pending },
        syncedBefore = { syncedBefore },
        marksVersion = { version },
        store = object : AssignmentMarkStep.MarkStore {
            override suspend fun ignored() = ignored
            override suspend fun completed() = completed
            override suspend fun replace(ignored: Set<String>, completed: Set<String>) {
                writes++
                version++
                this@BackgroundMarkStepTest.ignored = ignored
                this@BackgroundMarkStepTest.completed = completed
            }
        },
        unsent = { unsent.also { onUnsentRead() } },
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
        val outcome = step.pull(server(ignored = setOf("1")))

        assertTrue(outcome.taken)
        assertTrue(outcome.changed)
        assertEquals(setOf("1"), ignored)
    }

    @Test
    fun `a switch turned off while the waiting marks were read leaves this phone's marks alone`() = runTest {
        completed = setOf("2")
        onUnsentRead = { switchOn = false }

        val outcome = step.pull(server(ignored = setOf("1")))

        assertFalse(outcome.taken)
        assertEquals(0, writes)
        assertEquals(setOf("2"), completed)
    }

    @Test
    fun `a tap made since the marks were read stops the write, for the next sync to merge`() = runTest {
        onUnsentRead = {
            // Marked done on Home meanwhile.
            completed = setOf("2")
            version++
        }

        val outcome = step.pull(server(ignored = setOf("1")))

        assertFalse(outcome.taken)
        assertEquals(setOf("2"), completed)
    }

    @Test
    fun `a waiting mark keeps the status its tap set`() = runTest {
        unsent = mapOf("3" to STATUS_COMPLETED)

        step.pull(server(ignored = setOf("3")))

        assertEquals(setOf("3"), completed)
        assertEquals(emptySet<String>(), ignored)
    }

    @Test
    fun `with the switch off, the check pending or a first sync nothing is written`() = runTest {
        switchOn = false
        assertFalse(step.pull(server(ignored = setOf("1"))).taken)
        switchOn = true
        pending = setOf("assignments")
        assertFalse(step.pull(server(ignored = setOf("1"))).taken)
        pending = emptySet()
        syncedBefore = false
        ignored = setOf("9")
        assertFalse(step.pull(server()).taken)

        assertEquals(0, writes)
    }
}
