package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.testSignIn
import java.io.IOException

/**
 * Home's assignment-mark step ([AssignmentMarkStep]): with the Assignments
 * sync switch off a full sync leaves this phone's done and ignored marks
 * alone, as on iOS (tigerduck-app-android#188, #209), and the first-sync
 * upload runs once per account (#155).
 */
class AssignmentMarkStepTest {

    private var switchOn = true
    private var pending = emptySet<String>()
    private var syncedBefore = false
    private var ignored = emptySet<String>()
    private var completed = emptySet<String>()
    private var writes = 0
    private val sent = mutableListOf<Pair<Int, String>>()
    private var send: suspend (Int, String) -> Unit = { id, status -> sent += id to status }

    /** The sign-in each PATCH was pinned to. */
    private val signIns = mutableListOf<SignIn>()
    private val pinned = testSignIn()

    /** Runs as the step reads this phone's completed marks, for a switch flipped mid-sync. */
    private var onRead: () -> Unit = {}

    /** Marks tapped here whose PATCH has not landed; see UnsentMarks. */
    private var unsent = emptySet<String>()

    private val step = AssignmentMarkStep(
        switchOn = { switchOn },
        pendingConflicts = { pending },
        syncedBefore = { syncedBefore },
        store = object : AssignmentMarkStep.MarkStore {
            override suspend fun ignored() = ignored
            override suspend fun completed() = completed.also { onRead() }
            override suspend fun replace(ignored: Set<String>, completed: Set<String>, signIn: SignIn) {
                writes++
                this@AssignmentMarkStepTest.ignored = ignored
                this@AssignmentMarkStepTest.completed = completed
            }
        },
        send = { id, status, signIn ->
            signIns += signIn
            send(id, status)
        },
        unsent = { unsent },
        keepUnsent = { id, _, _ -> unsent = unsent + id },
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

        val synced = step.pull(state, server(ignored = setOf("1")), syncsMarks = false, signIn = pinned)

        assertFalse(synced)
        assertEquals(0, writes)
        assertEquals(emptySet<String>(), ignored)
    }

    @Test
    fun `with the switch off this phone's marks are not sent up as a first sync`() = runTest {
        switchOn = false
        completed = setOf("2")

        step.pull(state, server(), syncsMarks = false, signIn = pinned)

        assertEquals(emptyList<Pair<Int, String>>(), sent)
    }

    @Test
    fun `a switch turned off while the sync ran leaves the marks alone`() = runTest {
        onRead = { switchOn = false }

        val synced = step.pull(state, server(ignored = setOf("1")), syncsMarks = true, signIn = pinned)

        assertFalse(synced)
        assertEquals(0, writes)
    }

    @Test
    fun `the assignments re-enable check holds the marks`() = runTest {
        pending = setOf("assignments")

        assertFalse(step.pull(state, server(ignored = setOf("1")), syncsMarks = true, signIn = pinned))
        assertEquals(0, writes)
    }

    @Test
    fun `the first-sync upload goes up while the re-enable check waits for an answer`() = runTest {
        // Decided for both apps (tigerduck-app-android#188): the one-time
        // upload does not wait for the check, as on iOS.
        pending = setOf("assignments")
        ignored = setOf("1")

        val synced = step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertTrue(synced)
        assertEquals(listOf(1 to STATUS_IGNORED), sent)
    }

    @Test
    fun `with the switch off the first-sync upload does not go up while the check waits either`() = runTest {
        switchOn = false
        pending = setOf("assignments")
        ignored = setOf("1")

        assertFalse(step.pull(state, server(), syncsMarks = false, signIn = pinned))
        assertEquals(emptyList<Pair<Int, String>>(), sent)
    }

    @Test
    fun `the account's first sync here sends this phone's marks up instead of taking the empty server`() = runTest {
        ignored = setOf("1")

        val synced = step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertTrue(synced)
        assertEquals(listOf(1 to STATUS_IGNORED), sent)
        assertEquals(setOf("1"), ignored)
    }

    @Test
    fun `a first sync with a mark that did not land is not counted`() = runTest {
        ignored = setOf("1")
        send = { _, _ -> throw IOException("offline") }

        assertFalse(step.pull(state, server(), syncsMarks = true, signIn = pinned))
    }

    @Test
    fun `after the first sync an empty server clears a stale copy instead of taking it back up`() = runTest {
        syncedBefore = true
        ignored = setOf("1")

        val synced = step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertTrue(synced)
        assertEquals(emptyList<Pair<Int, String>>(), sent)
        assertEquals(emptySet<String>(), ignored)
        assertEquals(emptySet<String>(), state.ignoredAssignmentIds.value)
    }

    @Test
    fun `a real disagreement is kept at this phone's value and asked about`() = runTest {
        syncedBefore = true
        completed = setOf("1")
        val snapshot = server(ignored = setOf("1"))

        step.pull(state, snapshot, syncsMarks = true, signIn = pinned)

        assertEquals(setOf("1"), completed)
        assertEquals(listOf("1"), state.conflicts.value.map { it.id })
        assertNotNull(state.pendingResult)
    }

    /** What Home shows belongs to the session on screen: the old run's marks and questions are not its own. */
    @Test
    fun `a run whose sign-in has ended shows nothing of its snapshot and asks nothing`() = runTest {
        syncedBefore = true
        completed = setOf("1")
        state.markedCompletedIds.value = setOf("1")

        step.pull(state, server(ignored = setOf("1", "2")), syncsMarks = true, signIn = SignInSeries().end())

        assertEquals(emptySet<String>(), state.ignoredAssignmentIds.value)
        assertEquals(setOf("1"), state.markedCompletedIds.value)
        assertEquals(emptyList<String>(), state.conflicts.value.map { it.id })
        assertNull(state.pendingResult)
    }

    @Test
    fun `keeping the server's marks after the switch went off writes nothing`() = runTest {
        switchOn = false
        completed = setOf("1")

        step.keepServer(state, server(ignored = setOf("1")), pinned)

        assertEquals(0, writes)
        assertEquals(setOf("1"), completed)
    }

    @Test
    fun `keeping the server's marks takes them`() = runTest {
        completed = setOf("1")

        step.keepServer(state, server(ignored = setOf("1")), pinned)

        assertEquals(setOf("1"), ignored)
        assertEquals(emptySet<String>(), completed)
    }

    @Test
    fun `a tap whose PATCH failed survives a sync of a server without it`() = runTest {
        // Marked done offline after the account's first sync: the server has
        // no marks, and the next sync must not take that as the truth.
        syncedBefore = true
        completed = setOf("3")
        state.markedCompletedIds.value = setOf("3")
        unsent = setOf("3")

        step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertEquals(setOf("3"), completed)
        assertEquals(setOf("3"), state.markedCompletedIds.value)
    }

    @Test
    fun `keeping the server's marks keeps a tap that has not reached it`() = runTest {
        ignored = setOf("1")
        state.ignoredAssignmentIds.value = setOf("1")
        unsent = setOf("1")

        step.keepServer(state, server(completed = setOf("2")), pinned)

        assertEquals(setOf("1"), ignored)
        assertEquals(setOf("2"), completed)
    }

    @Test
    fun `the first-sync upload is pinned to the sign-in the sync began under`() = runTest {
        // A sign-out and another sign-in during the upload must not send the
        // rest of these marks with the next account's session.
        ignored = setOf("1")
        completed = setOf("2")

        step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertEquals(listOf(pinned, pinned), signIns)
    }

    @Test
    fun `a first-sync mark that did not land outlives the next sync`() = runTest {
        ignored = setOf("1", "2")
        state.ignoredAssignmentIds.value = setOf("1", "2")
        send = { id, status ->
            if (id == 2) throw IOException("offline")
            sent += id to status
        }

        assertFalse(step.pull(state, server(), syncsMarks = true, signIn = pinned))
        // The server now has 1, so the next sync is no first sync.
        step.pull(state, server(ignored = setOf("1")), syncsMarks = true, signIn = pinned)

        assertEquals(setOf("1", "2"), ignored)
    }

    @Test
    fun `a mark cleared while its first-sync PATCH was out is not kept to go up again`() = runTest {
        ignored = setOf("1")
        state.ignoredAssignmentIds.value = setOf("1")
        send = { _, _ ->
            // The user clears it on Home meanwhile; that tap goes up itself.
            state.ignoredAssignmentIds.value = emptySet()
            throw IOException("offline")
        }

        step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertEquals("nothing kept to undo the clear", emptySet<String>(), unsent)
    }

    @Test
    fun `a mark whose own tap is still going up is left to that tap`() = runTest {
        ignored = setOf("1")
        state.ignoredAssignmentIds.value = setOf("1")
        state.pendingOverrides += "1"
        send = { _, _ -> throw IOException("offline") }

        step.pull(state, server(), syncsMarks = true, signIn = pinned)

        assertEquals(emptySet<String>(), unsent)
    }

    private companion object {
    }

    /** Home's tap of [id] as [status]: on screen and in flight at once, then its own save. */
    private fun tap(id: String, status: String) {
        state.pendingOverrides += id
        state.ignoredAssignmentIds.value = if (status == STATUS_IGNORED) state.ignoredAssignmentIds.value + id else state.ignoredAssignmentIds.value - id
        ignored = if (status == STATUS_IGNORED) ignored + id else ignored - id
    }

    /**
     * The mark step over a store where [tap] lands as the pull goes to write its marks, once the
     * pull has read them: at the [atRead]th read of the marks, its write's own under the lock (a
     * `/v3` pull reads them once before), or as a store with no such step writes. Fires once.
     */
    private fun racingStep(atRead: Int = 2, afterWrite: Boolean = false, tap: () -> Unit): AssignmentMarkStep {
        var reads = 0
        var tapped = false
        val once = {
            if (!tapped) {
                tapped = true
                tap()
            }
        }
        return AssignmentMarkStep(
            switchOn = { switchOn },
            pendingConflicts = { pending },
            syncedBefore = { syncedBefore },
            store = object : AssignmentMarkStep.MarkStore {
                override suspend fun ignored(): Set<String> {
                    if (++reads == atRead) once()
                    return ignored
                }
                override suspend fun completed() = completed
                override suspend fun replace(ignored: Set<String>, completed: Set<String>, signIn: SignIn) {
                    if (!afterWrite) once()
                    this@AssignmentMarkStepTest.ignored = ignored
                    this@AssignmentMarkStepTest.completed = completed
                    if (afterWrite) once()
                }
            },
            send = { _, _, _ -> },
            unsent = { unsent },
            keepUnsent = { _, _, _ -> },
        )
    }

    /**
     * A tap whose own save lands as the pull goes to write its marks, once the pull has read them:
     * the pull writes each mark it changes onto the marks as they stand, and not the tapped one,
     * whose PATCH is under way, so the tap stays on disk and on screen, beside the pull's mark.
     */
    @Test
    fun `a mark tapped while the pull writes the marks stays, on screen and in the store`() = runTest {
        syncedBefore = true
        ignored = setOf("7")
        state.ignoredAssignmentIds.value = setOf("7")

        racingStep { tap("8", STATUS_IGNORED) }.pull(state, server(ignored = setOf("7", "9")), syncsMarks = true, signIn = pinned)

        assertEquals("in the store", setOf("7", "8", "9"), ignored)
        assertEquals("on screen", setOf("7", "8", "9"), state.ignoredAssignmentIds.value)
    }

    /**
     * A tap made as the pull's write lands, its own save after it: Home shows the marks as written,
     * but the tapped one, in flight, as Home holds it.
     */
    @Test
    fun `a mark tapped as the pull's write lands stays on screen`() = runTest {
        syncedBefore = true
        ignored = setOf("7")
        state.ignoredAssignmentIds.value = setOf("7")

        racingStep(atRead = 0, afterWrite = true) { tap("8", STATUS_IGNORED) }
            .pull(state, server(ignored = setOf("7", "9")), syncsMarks = true, signIn = pinned)

        assertEquals("in the store, its own save after the pull's", setOf("7", "8", "9"), ignored)
        assertEquals("on screen", setOf("7", "8", "9"), state.ignoredAssignmentIds.value)
    }

    /**
     * On `/v4` (LWW D12) the snapshot's marks are taken, a mark the two sides disagree on
     * included, with no conflict to offer, and a mark tapped as the pull writes stays.
     */
    @Test
    fun `on v4 a mark tapped during the pull stays, the rest are the snapshot's, and no conflict is offered`() = runTest {
        syncedBefore = true
        ignored = setOf("7")
        state.ignoredAssignmentIds.value = setOf("7")
        val holds = org.ntust.app.tigerduck.sync.reconcile.PullHolds(1L, emptySet(), org.ntust.app.tigerduck.sync.AckClock())

        val synced = racingStep(atRead = 1) { tap("8", STATUS_IGNORED) }
            .pull(state, server(ignored = setOf("9"), completed = setOf("7")), syncsMarks = true, signIn = pinned, holds = holds)

        assertTrue(synced)
        assertEquals(setOf("8", "9"), ignored)
        assertEquals(setOf("7"), completed)
        assertEquals(setOf("8", "9"), state.ignoredAssignmentIds.value)
        assertEquals(setOf("7"), state.markedCompletedIds.value)
        assertEquals(emptyList<AssignmentSyncConflict>(), state.conflicts.value)
    }
}
