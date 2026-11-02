package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries

@OptIn(ExperimentalCoroutinesApi::class)
class ReenableResolutionsTest {

    private val dispatcher = StandardTestDispatcher()
    private val failures = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> failures += e },
    )
    private val series = SignInSeries()
    private val session = AccountSession(series, scope)
    private val steps = mutableListOf<String>()

    private fun answer(signIn: SignIn = series.current, signedIn: Boolean = true, answer: suspend () -> Unit = { steps += "answer" }) =
        scope.answerOnSession(
            session,
            signIn,
            signedIn = { signedIn },
            answer = answer,
            settle = { steps += "settle" },
            after = { steps += "after" },
        )

    private fun run() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `an answer that goes through settles, and what follows it runs`() {
        answer()
        run()

        assertEquals(listOf("answer", "settle", "after"), steps)
    }

    @Test
    fun `a sign-out during an answer stops it, lets go of what it held, and nothing follows`() {
        var finished = false
        answer {
            delay(1_000)
            finished = true
        }
        dispatcher.scheduler.runCurrent()

        series.end()
        session.cancelWork()
        run()

        assertFalse(finished)
        assertEquals(listOf("settle"), steps)
    }

    @Test
    fun `an answer a sign-out overtook before it started still lets go of what it held`() {
        // Nothing of the answer runs, a finally in it included.
        series.end()

        answer()
        run()

        assertEquals(listOf("settle"), steps)
    }

    @Test
    fun `an answer given while signed out does nothing, and lets go of what it held`() {
        answer(signedIn = false)
        run()

        assertEquals(listOf("settle"), steps)
    }

    @Test
    fun `an answer that fails lets go of what it held, and nothing follows`() {
        answer { error("the send failed") }
        run()

        assertEquals(listOf("settle"), steps)
        assertEquals(1, failures.size)
    }

    @Test
    fun `a sign-out as the answer ends leaves what follows it unstarted`() {
        answer {
            steps += "answer"
            series.end()
        }
        run()

        assertEquals(listOf("answer", "settle"), steps)
    }

    @Test
    fun `a check runs at once when no answer is being applied`() {
        assertNotNull(ReenableResolutions().checkStarting { })
    }

    @Test
    fun `a check waits for the answer being applied and runs when it finishes`() {
        val resolutions = ReenableResolutions()
        var checks = 0
        resolutions.start()

        assertNull(resolutions.checkStarting { checks++ })
        assertEquals(0, checks)
        // Letting the hold go here would end it under the check's own dialog.
        assertFalse(resolutions.finish())
        assertEquals(1, checks)
        assertNotNull(resolutions.checkStarting { checks++ })
    }

    @Test
    fun `the hold is let go when the last answer finishes and no check waits`() {
        val resolutions = ReenableResolutions()
        resolutions.start()

        assertTrue(resolutions.finish())
    }

    @Test
    fun `an answer finishing first keeps the hold for one still being applied`() {
        val resolutions = ReenableResolutions()
        var checks = 0
        resolutions.start()
        resolutions.start()
        resolutions.checkStarting { checks++ }

        assertFalse(resolutions.finish())
        assertEquals(0, checks)
        assertFalse(resolutions.finish())
        assertEquals(1, checks)
    }

    @Test
    fun `a check keeps its result when no answer starts before it is done`() {
        val resolutions = ReenableResolutions()
        val token = resolutions.checkStarting { }!!

        assertTrue(resolutions.isCurrent(token))
    }

    @Test
    fun `a check overtaken by an answer drops its result`() {
        val resolutions = ReenableResolutions()
        val token = resolutions.checkStarting { }!!
        resolutions.start()
        resolutions.finish()

        assertFalse(resolutions.isCurrent(token))
    }

    @Test
    fun `a category switched on while an answered dialog's check ran is checked once the answer is applied`() {
        // As the Settings screens run them: each check reads the pending set as it starts.
        val resolutions = ReenableResolutions()
        var pending = setOf("courses")
        val checked = mutableListOf<Set<String>>()
        fun check(): Int? = resolutions.checkStarting { check() }?.also { checked += pending }

        val first = check()!!
        // Names switched back on while the courses check waits for the server.
        pending = pending + "course_names"
        val second = check()!!
        // The courses check finds a difference, and its dialog is answered before the second returns.
        assertTrue(resolutions.isCurrent(first))
        val answered = listOf("courses")
        val left = unanswered(pending, answered)
        pending = heldUntilAnswered(answered) + left
        resolutions.start()
        if (left.isNotEmpty()) check()

        assertFalse("the answer changed what the second compared", resolutions.isCurrent(second))
        assertEquals(setOf("course_names"), pending)
        resolutions.finish()
        assertEquals(listOf(setOf("courses"), setOf("courses", "course_names"), setOf("course_names")), checked)
    }

    @Test
    fun `a check that does not wait leaves a Settings screen's waiting check in place`() {
        // The owed check a sync starts must not take the place of the one a
        // Settings screen left waiting, whose dialog it would then never show.
        val resolutions = ReenableResolutions()
        val ran = mutableListOf<String>()
        resolutions.start()
        assertNull(resolutions.checkStarting { ran += "settings" })

        assertNull(resolutions.checkStarting(waits = false) { ran += "owed" })
        resolutions.finish()

        assertEquals(listOf("settings"), ran)
    }
}
