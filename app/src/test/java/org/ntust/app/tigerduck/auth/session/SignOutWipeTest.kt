package org.ntust.app.tigerduck.auth.session

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One count under one lock: the session opens only when no sign-out is under way. */
class SignOutWipeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val owed = SignOutOwed(JvmContext().getSharedPreferences("wipe-test", Context.MODE_PRIVATE))

    @After
    fun stop() = scope.cancel()

    /** Where a second sign-out lands in a first. */
    private enum class Stage { BEGUN, CLEARED, WIPING, WIPED, FINISHED }

    /**
     * Two sign-outs on one thread, wipes and all, so each stage is exact: the second one lands at
     * [landsAt] of the first. At every stage the session must be closed unless every sign-out that
     * has begun has also wiped.
     */
    private inner class Overlap(private val landsAt: Stage, closed: Boolean = false) {
        private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = AccountSession(SignInSeries(closed = closed), unconfined, owed = owed)
        private val wipe = SignOutWipe(wipe = ::wiping, session = session, scope = unconfined)
        private var landed = false

        var begun = 0
        var cleared = 0
        var wiped = 0
        var clearedAtLastWipe = 0
        val openTooEarly = mutableListOf<Stage>()

        private suspend fun wiping() {
            clearedAtLastWipe = cleared
            at(Stage.WIPING)
            at(Stage.WIPED)
            wiped++
        }

        private fun at(stage: Stage) {
            if (begun > wiped && session.isOpen) openTooEarly += stage
            if (stage == landsAt && !landed) {
                landed = true
                signOut()
            }
        }

        fun signOut() {
            wipe.begin(STUDENT)
            begun++
            assertTrue("on disk before anything is cleared", owed.owed && owed.replays(STUDENT))
            at(Stage.BEGUN)
            cleared++
            at(Stage.CLEARED)
            wipe.finish()
            at(Stage.FINISHED)
        }

        /** The marker a process death left, found at the start: the recovery in the first's place. */
        fun recovery() {
            owed.mark(null)
            begun++
            assertTrue(
                wipe.recover({ null }) {
                    at(Stage.BEGUN)
                    cleared++
                    true
                },
            )
            at(Stage.CLEARED)
            wipe.finish()
            at(Stage.FINISHED)
        }

        fun assertBothDone(stage: Stage) {
            assertEquals("the session is never open with a sign-out under way, second at $stage", emptyList<Stage>(), openTooEarly)
            assertEquals("each sign-out wiped, second at $stage", 2, wiped)
            assertEquals("a wipe ran after the last clearing, second at $stage", 2, clearedAtLastWipe)
            assertTrue("it opens in the end, second at $stage", session.isOpen)
            assertFalse("and the marker goes then, second at $stage", owed.owed)
        }
    }

    @Test
    fun `a second sign-out at any stage of the first holds the session until both have wiped`() {
        Stage.entries.forEach { stage -> Overlap(stage).apply { signOut() }.assertBothDone(stage) }
    }

    @Test
    fun `a sign-out at any stage of a recovery holds the session until both have wiped`() {
        Stage.entries.forEach { stage -> Overlap(stage, closed = true).apply { recovery() }.assertBothDone(stage) }
    }

    /** Sign-outs on the app's threads, each wipe held until a test completes its gate. */
    private inner class Held {
        val session = AccountSession(SignInSeries(), scope, owed = owed)
        val started = AtomicInteger(0)
        val wiped = AtomicInteger(0)
        val gates = List(3) { CompletableDeferred<Unit>() }
        val wipe = SignOutWipe(
            wipe = {
                val pass = started.getAndIncrement()
                gates[pass].await()
                wiped.incrementAndGet()
            },
            session = session,
            scope = scope,
        )

        fun signOut() {
            wipe.begin(STUDENT)
            wipe.finish()
        }

        suspend fun awaitStarted(passes: Int) = withTimeout(5_000) { while (started.get() < passes) delay(1) }
    }

    @Test
    fun `a sign-in waiting through two sign-outs goes only after the last wipe`() = runBlocking {
        val held = Held()
        held.signOut()
        held.awaitStarted(1)
        val signedIn = scope.async {
            held.session.awaitOpen()
            held.wiped.get()
        }

        held.signOut()
        held.gates[0].complete(Unit)
        held.awaitStarted(2)
        delay(100)
        assertFalse("the first wipe ended, and the second is still running", signedIn.isCompleted)

        held.gates[1].complete(Unit)
        assertEquals(2, withTimeout(5_000) { signedIn.await() })
    }

    @Test
    fun `wipes run one at a time`() = runBlocking {
        val held = Held()
        held.signOut()
        held.awaitStarted(1)

        held.signOut()
        delay(100)
        assertEquals("the second waits for the first", 1, held.started.get())

        held.gates[0].complete(Unit)
        held.awaitStarted(2)
        held.gates[1].complete(Unit)
        withTimeout(5_000) { held.session.awaitOpen() }
    }

    @Test
    fun `a sign-out after the last wipe has finished closes the session and wipes again`() = runBlocking {
        val held = Held()
        held.gates[0].complete(Unit)
        held.signOut()
        withTimeout(5_000) { held.session.awaitOpen() }

        held.signOut()
        assertFalse(held.session.isOpen)
        held.gates[1].complete(Unit)
        withTimeout(5_000) { held.session.awaitOpen() }
        assertEquals(2, held.wiped.get())
    }

    @Test
    fun `sign-outs racing each other and the wipes never find the session open, and it opens in the end`() = runBlocking {
        repeat(ROUNDS) { round ->
            val session = AccountSession(SignInSeries(), scope, owed = owed)
            val openFound = AtomicReference<String?>(null)
            val wiped = AtomicInteger(0)
            fun assertClosed(where: String) {
                if (session.isOpen) openFound.set(where)
            }
            val wipe = SignOutWipe(
                wipe = {
                    assertClosed("as a wipe started")
                    yield()
                    assertClosed("as a wipe ended")
                    wiped.incrementAndGet()
                },
                session = session,
                scope = scope,
            )
            List(SIGN_OUTS) {
                scope.launch {
                    wipe.begin(STUDENT)
                    assertClosed("as the account's clearing started")
                    yield()
                    assertClosed("as the account's clearing ended")
                    wipe.finish()
                }
            }.joinAll()
            withTimeout(5_000) {
                while (wiped.get() < SIGN_OUTS) delay(1)
                session.awaitOpen()
            }
            assertEquals("round $round", null, openFound.get())
            assertFalse("round $round", owed.owed)
        }
    }

    @Test
    fun `a wipe stopped with its scope leaves the session closed and the marker on for the next start`() = runBlocking {
        val ending = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = AccountSession(SignInSeries(), ending, owed = owed)
        val started = CompletableDeferred<Unit>()
        val wipe = SignOutWipe(
            wipe = {
                started.complete(Unit)
                delay(60_000)
            },
            session = session,
            scope = ending,
        )
        wipe.begin(STUDENT)
        wipe.finish()
        withTimeout(5_000) { started.await() }

        ending.cancel()
        delay(50)

        assertFalse(session.isOpen)
        assertTrue(owed.owed)
    }

    // ---- at process start ----

    private var clearings = 0
    private var wipes = 0

    private fun clearing(): Boolean {
        clearings++
        return true
    }

    private fun recoverable(session: AccountSession) = SignOutWipe(
        wipe = { wipes++ },
        session = session,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test
    fun `no marker on a normal launch costs nothing`() {
        val session = AccountSession(SignInSeries(), scope, owed = owed)
        val before = session.signIns.current

        val askedWhoIsStored = AtomicInteger(0)

        assertFalse(
            recoverable(session).recover({
                askedWhoIsStored.incrementAndGet()
                STUDENT
            }, ::clearing),
        )

        assertEquals(0, askedWhoIsStored.get())
        assertEquals(0, clearings)
        assertEquals(0, wipes)
        assertTrue(session.isOpen)
        assertTrue("the same sign-in goes on", before === session.signIns.current && before.isCurrent)
        assertFalse(owed.owed)
    }

    @Test
    fun `a marker of the student stored runs the sign-out's clearing, then its wipe`() {
        owed.mark(STUDENT)
        val session = AccountSession(SignInSeries(closed = true), scope, owed = owed)
        val wipe = recoverable(session)

        assertTrue(wipe.recover({ STUDENT }, ::clearing))

        assertEquals(1, clearings)
        assertFalse("closed until the wipe, which the caller starts after the migration", session.isOpen)
        assertTrue(owed.owed)
        assertEquals(0, wipes)

        wipe.finish()

        assertEquals(1, wipes)
        assertTrue(session.isOpen)
        assertFalse(owed.owed)
    }

    @Test
    fun `a marker with nobody stored runs the clearing all the same, for what is left of it`() {
        owed.mark(STUDENT)
        val session = AccountSession(SignInSeries(closed = true), scope, owed = owed)

        assertTrue(recoverable(session).recover({ null }, ::clearing))

        assertEquals(1, clearings)
    }

    @Test
    fun `a marker of someone else's clears nothing, wipes nothing and opens the session`() {
        owed.mark(STUDENT)
        val session = AccountSession(SignInSeries(closed = true), scope, owed = owed)

        assertFalse(recoverable(session).recover({ LATER_STUDENT }, ::clearing))

        assertEquals(0, clearings)
        assertEquals(0, wipes)
        assertTrue(session.isOpen)
        assertFalse(owed.owed)
    }

    /** One start over the marker on disk, whose clearing answers [cleared]. */
    private fun start(cleared: Boolean): AccountSession {
        val session = AccountSession(SignInSeries(closed = owed.owed), scope, owed = owed)
        val wipe = recoverable(session)
        assertTrue(wipe.recover({ STUDENT }) { cleared })
        wipe.finish()
        return session
    }

    @Test
    fun `a recovery whose clearing failed wipes, and leaves the marker and the sign-in for the next start`() {
        owed.mark(STUDENT)

        val session = start(cleared = false)

        assertEquals(1, wipes)
        assertFalse(session.isOpen)
        assertTrue(owed.owed)
    }

    @Test
    fun `the next start finishes what a failed clearing left`() {
        owed.mark(STUDENT)
        start(cleared = false)

        val session = start(cleared = true)

        assertTrue(session.isOpen)
        assertFalse(owed.owed)
    }

    @Test
    fun `a clearing that keeps failing holds the sign-in for two starts, and the third opens it`() {
        owed.mark(STUDENT)

        assertFalse(start(cleared = false).isOpen)
        assertFalse(start(cleared = false).isOpen)
        val third = start(cleared = false)

        assertTrue(third.isOpen)
        assertFalse(owed.owed)
    }

    @Test
    fun `a sign-out whose clearing failed holds the same way, and a later one that went pays for it`() {
        val session = AccountSession(SignInSeries(), scope, owed = owed)
        val wipe = recoverable(session)

        wipe.begin(STUDENT)
        wipe.finish(cleared = false)
        assertFalse(session.isOpen)
        assertTrue(owed.owed)

        wipe.begin(null)
        wipe.finish()
        assertTrue(session.isOpen)
        assertFalse(owed.owed)
    }

    private companion object {
        const val STUDENT = "B00015998"
        const val LATER_STUDENT = "B00015999"
        const val SIGN_OUTS = 3
        const val ROUNDS = 300
    }
}
