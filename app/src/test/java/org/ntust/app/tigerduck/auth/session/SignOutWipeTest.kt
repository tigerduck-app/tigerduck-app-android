package org.ntust.app.tigerduck.auth.session

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.atomic.AtomicInteger

/** One wipe at a time, and the session opens only when none is left. */
class SignOutWipeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val owed = SignOutOwed(JvmContext().getSharedPreferences("wipe-test", Context.MODE_PRIVATE))
    private val session = AccountSession(SignInSeries(closed = true), scope, owed = owed)

    @After
    fun stop() = scope.cancel()

    private val passes = AtomicInteger(0)
    private var gate = CompletableDeferred<Unit>()
    private val firstStarted = CompletableDeferred<Unit>()

    private fun wipe(dropped: () -> Unit = {}) = SignOutWipe(
        clearing = {
            if (passes.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                gate.await()
            }
        },
        session = session,
        scope = scope,
        storedStudentId = { null },
        dropStoredAccount = dropped,
    )

    @Test
    fun `a sign-out during a wipe queues one more, and the session opens only when none is left`() = runBlocking {
        owed.mark("B00015999")
        val wipe = wipe()
        wipe.start()
        withTimeout(5_000) { firstStarted.await() }

        // The second sign-out lands while the first wipe is still running; a third joins it.
        wipe.start()
        wipe.start()
        gate.complete(Unit)

        withTimeout(5_000) { session.awaitOpen() }
        assertEquals("the queued pass ran, and the joined ones coalesced into it", 2, passes.get())
        assertFalse(owed.owed)
    }

    @Test
    fun `the session stays closed while the second pass is pending`() = runBlocking {
        val wipe = wipe()
        wipe.start()
        withTimeout(5_000) { firstStarted.await() }
        wipe.start()
        gate.complete(Unit)
        // Whenever it is looked at, the session is open only after both passes ran.
        repeat(200) {
            if (session.signIns.isOpen.value) assertEquals(2, passes.get())
            delay(1)
        }
        assertTrue(session.signIns.isOpen.value)
    }

    @Test
    fun `a wipe started again after the last one finished runs again`() = runBlocking {
        gate.complete(Unit)
        val wipe = wipe()
        wipe.start()
        withTimeout(5_000) { session.awaitOpen() }
        session.signIns.end()
        wipe.start()
        withTimeout(5_000) { session.awaitOpen() }
        assertEquals(2, passes.get())
    }
}
