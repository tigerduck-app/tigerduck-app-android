package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.cache.AssignmentMarks
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import org.ntust.app.tigerduck.util.testSignIn

/**
 * Home's assignment-mark step ([AssignmentMarkStep]): with the Assignments sync switch off a full
 * sync leaves this phone's done and ignored marks alone, as on iOS (tigerduck-app-android#188,
 * #209), and otherwise the snapshot's marks are taken field by field (LWW D12), but each this
 * phone holds: a tap whose PATCH is under way or waits to go up.
 */
class AssignmentMarkStepTest {

    private var switchOn = true
    private var pending = emptySet<String>()
    private var ignored = emptySet<String>()
    private var completed = emptySet<String>()
    private var writes = 0
    private val pinned = testSignIn()

    /** Runs as the step reads the re-enable hold, for a switch flipped mid-sync. */
    private var onRead: () -> Unit = {}

    /** Marks tapped here whose PATCH has not landed, by assignment id, with the status the tap set; see UnsentMarks. */
    private var unsent = emptyMap<String, String>()

    // The store's one step: the marks as stored and those waiting, read and written under one lock;
    // nothing once the sign-in has ended, as DataCache.updateAssignmentMarks.
    private fun markStore(beforeRead: () -> Unit = {}, afterWrite: () -> Unit = {}) = AssignmentMarkStep.MarkStore { signIn, change ->
        if (!signIn.isCurrent) return@MarkStore null
        beforeRead()
        val waiting = unsent.map { (id, status) -> UnsentMark(id, status) }
        change(AssignmentMarks(ignored, completed), waiting).also {
            writes++
            ignored = it.ignored
            completed = it.completed
            afterWrite()
        }
    }

    private fun step(store: AssignmentMarkStep.MarkStore = markStore()) = AssignmentMarkStep(
        switchOn = { switchOn },
        pendingConflicts = { pending.also { onRead() } },
        store = store,
    )

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    /** A pull that holds nothing but what waits. */
    private val holds = PullHolds(1L, AckClock())

    private fun server(ignored: Set<String> = emptySet(), completed: Set<String> = emptySet()) = BackendSyncResult(
        ignoredIds = ignored,
        completedIds = completed,
        courseOverrides = emptyList(),
        serverCourseNos = emptySet(),
        currentRevision = 1,
    )

    @Test
    fun `with the switch off the server's marks are not taken`() = runTest {
        switchOn = false

        val synced = step().pull(state, server(ignored = setOf("1")), syncsMarks = false, signIn = pinned, holds = holds)

        assertFalse(synced)
        assertEquals(0, writes)
        assertEquals(emptySet<String>(), ignored)
    }

    @Test
    fun `a switch turned off while the sync ran leaves the marks alone`() = runTest {
        onRead = { switchOn = false }

        val synced = step().pull(state, server(ignored = setOf("1")), syncsMarks = true, signIn = pinned, holds = holds)

        assertFalse(synced)
        assertEquals(0, writes)
    }

    @Test
    fun `the assignments re-enable check holds the marks`() = runTest {
        pending = setOf("assignments")

        assertFalse(step().pull(state, server(ignored = setOf("1")), syncsMarks = true, signIn = pinned, holds = holds))
        assertEquals(0, writes)
    }

    @Test
    fun `after the first sync an empty server clears a stale copy instead of taking it back up`() = runTest {
        ignored = setOf("1")

        val synced = step().pull(state, server(), syncsMarks = true, signIn = pinned, holds = holds)

        assertTrue(synced)
        assertEquals(emptySet<String>(), ignored)
        assertEquals(emptySet<String>(), state.ignoredAssignmentIds.value)
    }

    /** What Home shows belongs to the session on screen: the old run's marks and questions are not its own. */
    @Test
    fun `a run whose sign-in has ended shows nothing of its snapshot and asks nothing`() = runTest {
        completed = setOf("1")
        state.markedCompletedIds.value = setOf("1")

        step().pull(state, server(ignored = setOf("1", "2")), syncsMarks = true, signIn = SignInSeries().end(), holds = holds)

        assertEquals(emptySet<String>(), state.ignoredAssignmentIds.value)
        assertEquals(setOf("1"), state.markedCompletedIds.value)
        assertEquals(emptyList<String>(), state.conflicts.value.map { it.id })
        assertNull(state.pendingResult)
    }

    @Test
    fun `a tap whose PATCH failed survives a sync of a server without it`() = runTest {
        // Marked done offline after the account's first sync: the server has
        // no marks, and the next sync must not take that as the truth.
        completed = setOf("3")
        state.markedCompletedIds.value = setOf("3")
        unsent = mapOf("3" to STATUS_COMPLETED)

        step().pull(state, server(), syncsMarks = true, signIn = pinned, holds = holds)

        assertEquals(setOf("3"), completed)
        assertEquals(setOf("3"), state.markedCompletedIds.value)
    }

    /** Home's tap of [id] as [status]: on screen and in flight at once, then its own save. */
    private fun tap(id: String, status: String) {
        state.pendingOverrides += id
        state.ignoredAssignmentIds.value = if (status == STATUS_IGNORED) state.ignoredAssignmentIds.value + id else state.ignoredAssignmentIds.value - id
        ignored = if (status == STATUS_IGNORED) ignored + id else ignored - id
    }

    /**
     * The mark step over a store where [tap] lands as the pull goes to write its marks: before
     * the write's own read under the lock, or with [afterWrite] just after the write. Fires once.
     */
    private fun racingStep(afterWrite: Boolean = false, tap: () -> Unit): AssignmentMarkStep {
        var tapped = false
        val once = {
            if (!tapped) {
                tapped = true
                tap()
            }
        }
        return step(markStore(beforeRead = { if (!afterWrite) once() }, afterWrite = { if (afterWrite) once() }))
    }

    /**
     * A tap whose own save lands as the pull goes to write its marks: the pull writes each mark it
     * changes onto the marks as they stand, and not the tapped one, whose PATCH is under way, so
     * the tap stays on disk and on screen, beside the pull's mark.
     */
    @Test
    fun `a mark tapped while the pull writes the marks stays, on screen and in the store`() = runTest {
        ignored = setOf("7")
        state.ignoredAssignmentIds.value = setOf("7")

        racingStep { tap("8", STATUS_IGNORED) }.pull(state, server(ignored = setOf("7", "9")), syncsMarks = true, signIn = pinned, holds = holds)

        assertEquals("in the store", setOf("7", "8", "9"), ignored)
        assertEquals("on screen", setOf("7", "8", "9"), state.ignoredAssignmentIds.value)
    }

    /**
     * A tap made as the pull's write lands, its own save after it: Home shows the marks as written,
     * but the tapped one, in flight, as Home holds it.
     */
    @Test
    fun `a mark tapped as the pull's write lands stays on screen`() = runTest {
        ignored = setOf("7")
        state.ignoredAssignmentIds.value = setOf("7")

        racingStep(afterWrite = true) { tap("8", STATUS_IGNORED) }
            .pull(state, server(ignored = setOf("7", "9")), syncsMarks = true, signIn = pinned, holds = holds)

        assertEquals("in the store, its own save after the pull's", setOf("7", "8", "9"), ignored)
        assertEquals("on screen", setOf("7", "8", "9"), state.ignoredAssignmentIds.value)
    }

    /**
     * The snapshot's marks are taken (LWW D12), a mark the two sides disagree on included, with no
     * conflict to offer, and a mark tapped as the pull writes stays.
     */
    @Test
    fun `on v4 a mark tapped during the pull stays, the rest are the snapshot's, and no conflict is offered`() = runTest {
        ignored = setOf("7")
        state.ignoredAssignmentIds.value = setOf("7")

        val synced = racingStep { tap("8", STATUS_IGNORED) }
            .pull(state, server(ignored = setOf("9"), completed = setOf("7")), syncsMarks = true, signIn = pinned, holds = holds)

        assertTrue(synced)
        assertEquals(setOf("8", "9"), ignored)
        assertEquals(setOf("7"), completed)
        assertEquals(setOf("8", "9"), state.ignoredAssignmentIds.value)
        assertEquals(setOf("7"), state.markedCompletedIds.value)
        assertEquals(emptyList<AssignmentSyncConflict>(), state.conflicts.value)
    }
}
