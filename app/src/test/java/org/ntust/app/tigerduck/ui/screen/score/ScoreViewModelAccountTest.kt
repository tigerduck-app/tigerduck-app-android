@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.score

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.auth.SignedInState
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.data.model.ScoreReport
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.testSession

/**
 * Score follows the signed-in account: it loads after a sign-in from its own page, and the
 * previous student's report never stays for the next one. The iPhone app had the same gap
 * (tigerduck-app/tigerduck-app#263).
 */
class ScoreViewModelAccountTest {
    @get:Rule val main = MainDispatcherRule()

    private val session = testSession()
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

    private fun viewModel(accountChanges: Flow<Boolean> = signedIn) = ScoreViewModel(
        authState = signedIn,
        accountChanges = accountChanges,
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
        session = session,
        strings = { "string $it" },
        rosterCourses = { emptyList() },
    )

    @Test
    fun `the demo account has no scores to ask for, and shows the empty state rather than a failure`() {
        session.signIns.startDemo()
        signIn("B00015999")
        val vm = viewModel()

        vm.load()

        assertEquals(emptyList<String>(), fetches.toList())
        assertEquals(null, vm.errorMessage.value)
    }

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
    fun `a sign-out and a sign-in landing before it runs are both seen`() {
        val session = AccountSession(SignInSeries(), CoroutineScope(SupervisorJob()))
        val state = SignedInState(true).also { session.signedInFrom(it.signedIn) }
        val late = StandardTestDispatcher()
        Dispatchers.setMain(late)
        signIn("B11300001")
        val vm = viewModel(session.changes)
        late.scheduler.advanceUntilIdle()
        assertEquals("B11300001", vm.report.value.student)
        val first = fetches.size

        // The same student out and back in: their stored copy went with the sign-out.
        state.set(false) { session.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, session.signIns.current, null)) }
        state.set(true) { session.emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = false)) }
        late.scheduler.advanceUntilIdle()

        assertEquals("loaded again for the new sign-in", first + 1, fetches.size)
        assertEquals("B11300001", vm.report.value.student)
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
