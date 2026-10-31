package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.MarkSender
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.settings.categoriesSwitchedOn
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * Home's mark step as `HomeBackendSync` builds it ([AssignmentMarkStep.over]),
 * over real preferences and files, with only the backend faked.
 */
class HomeMarkSyncTest {

    private lateinit var prefs: AppPreferences
    private lateinit var cache: DataCache
    private lateinit var unsent: UnsentMarks
    private lateinit var step: AssignmentMarkStep

    private var backendUp = true
    private val sent = mutableListOf<Pair<Int, String>>()

    private val send: suspend (Int, String) -> Unit = { id, status ->
        if (!backendUp) throw IOException("offline")
        sent += id to status
    }

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    @Before
    fun setUp() {
        val context = JvmContext()
        prefs = AppPreferences(context)
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        // Sending as with TigerSync on, which the fdroid build never is.
        unsent = UnsentMarks(
            cache,
            MarkSender { id, status, _, _ ->
                send(id, status)
                true
            },
            records = { prefs.syncAssignments },
            sends = { prefs.syncAssignments },
            signInGeneration = { 0 },
        )
        step = AssignmentMarkStep.over(prefs, cache, unsent) { id, status, _ -> send(id, status) }
    }

    private fun server(ignored: Set<String> = emptySet()) = BackendSyncResult(
        ignoredIds = ignored,
        completedIds = emptySet(),
        courseOverrides = emptyList(),
        serverCourseNos = emptySet(),
        currentRevision = 1,
    )

    /** What `HomeBackendSync.pull` passes: the switch as read with the other categories. */
    private suspend fun fullSync(result: BackendSyncResult) =
        step.pull(state, result, syncsMarks = "assignments" in categoriesSwitchedOn(prefs), signIn = 0, generation = cache.userDataGeneration)

    @Test
    fun `with the Assignments switch off a full sync leaves this phone's marks alone`() = runTest {
        // tigerduck-app-android#209: the switch keeps this phone's marks
        // apart from the server's, as on iOS.
        prefs.syncAssignments = false
        cache.saveMarkedCompletedAssignments(setOf("1"))

        fullSync(server(ignored = setOf("2")))

        assertEquals(setOf("1"), cache.loadMarkedCompletedAssignments())
        assertEquals(emptySet<String>(), cache.loadIgnoredAssignments())
    }

    @Test
    fun `a mark tapped while its PATCH fails outlives the next sync and goes up after it`() = runTest {
        prefs.assignmentMarksSyncedOnce = true
        backendUp = false
        // What HomeViewModel.toggleOverride does for a tap.
        cache.saveMarkedCompletedAssignments(setOf("3"))
        state.markedCompletedIds.value = setOf("3")
        unsent.send("3", STATUS_COMPLETED)

        // The next sync: the retry fails too, and the server holds no marks.
        unsent.retry()
        fullSync(server())

        assertEquals(setOf("3"), cache.loadMarkedCompletedAssignments())

        backendUp = true
        unsent.retry()

        assertEquals(listOf(3 to STATUS_COMPLETED), sent)
        assertEquals(emptySet<String>(), unsent.ids())
    }
}
