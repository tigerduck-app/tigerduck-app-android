@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.score

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.mail.MainDispatcherRule

/**
 * Score follows the signed-in account: it loads after a sign-in from its own page, and the
 * previous student's report never stays for the next one. The iPhone app had the same gap
 * (tigerduck-app/tigerduck-app#263).
 */
class ScoreViewModelAccountTest {
    @get:Rule val main = MainDispatcherRule()

    private val signedIn = MutableStateFlow(false)
    private var studentId: String? = null
    private var online = true

    /** Each student's report as the school answers it; a held one waits for the test. */
    private val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val fetches = mutableListOf<String>()

    private fun report(of: String) = ScoreReport.EMPTY.copy(
        student = of,
        courses = listOf(CourseGrade(term = "1141", code = "CS1001301", name = "Course of $of")),
    )

    private fun viewModel() = ScoreViewModel(
        authState = signedIn,
        storedStudentId = { studentId },
        storedPassword = { studentId?.let { "password" } },
        cachedSnapshot = { null },
        fetchReport = { id, _, _ ->
            fetches += id
            // Like the school's page load, which cancelling does not stop once it is on the wire.
            held[id]?.let { withContext(NonCancellable) { it.await() } }
            report(id)
        },
        isOnline = { online },
        isDemo = { false },
        strings = { "string $it" },
        rosterCourses = { emptyList() },
    )

    private fun signIn(id: String) {
        studentId = id
        signedIn.value = true
    }

    private fun signOut() {
        studentId = null
        signedIn.value = false
    }

    @Test
    fun `signing in from the Score page loads the report without a pull`() {
        val vm = viewModel()
        // The page opens signed out, and the user signs in from its lock.
        vm.load()
        signIn("B11300001")
        vm.load()

        assertEquals("B11300001", vm.report.value.student)
    }

    @Test
    fun `the next student never sees the previous student's report`() {
        signIn("B11300001")
        val vm = viewModel()
        vm.load()
        assertEquals("B11300001", vm.report.value.student)

        signOut()
        assertEquals(ScoreReport.EMPTY, vm.report.value)
        assertEquals(emptySet<String>(), vm.collapsedTerms.value)

        signIn("B11300002")
        assertEquals("B11300002", vm.report.value.student)
    }

    @Test
    fun `a fetch for the student who signed out does not land on the next one`() {
        held["B11300001"] = CompletableDeferred()
        signIn("B11300001")
        val vm = viewModel()
        vm.load()

        signOut()
        signIn("B11300002")
        held.getValue("B11300001").complete(Unit)

        assertEquals("B11300002", vm.report.value.student)
        assertEquals(listOf("B11300001", "B11300002"), fetches)
    }

    @Test
    fun `a refresh offline says the network is unavailable`() = runTest {
        signIn("B11300001")
        online = false
        val vm = viewModel()
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.noNetworkEvent.collect { events += it }
        }

        vm.triggerRefresh()

        assertEquals(1, events.size)
        assertEquals(emptyList<String>(), fetches)
    }
}
