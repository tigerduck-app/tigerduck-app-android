package org.ntust.app.tigerduck.wear.data

import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.LibraryServiceError
import org.ntust.app.tigerduck.shared.tls.PinDomain
import org.ntust.app.tigerduck.shared.tls.PinMismatch
import org.ntust.app.tigerduck.shared.tls.PinMismatchException
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The watch's code page when the phone signs the library account out or hands
 * over another one while a code is being fetched: the fetch is dropped
 * (tigerduck-app/tigerduck-app-android#293), and the page says so and starts
 * afresh for the next account instead of naming an exception until reopened.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryQRControllerTest {

    private val accounts = MutableStateFlow<String?>("B10000001")
    private val answers = ArrayDeque<suspend () -> String>()

    // Unconfined, so each step below has run by the time the next line reads the state.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controller = LibraryQRController(
        generate = { answers.removeFirst()() },
        scope = scope,
        accountChangedMessage = ACCOUNT_CHANGED,
        renderContext = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a code dropped as its account changed says so rather than naming the exception`() {
        answers += { throw LibraryServiceError.CredentialsNotFound() }

        controller.start(64, accounts)

        assertEquals(ACCOUNT_CHANGED, controller.error.value)
        assertNull(controller.qrBitmap.value)
    }

    @Test
    fun `a pin mismatch says to open TigerDuck on the phone, which settles it`() {
        val mismatch = PinMismatch("library", "api.lib.ntust.edu.tw", listOf(PinDomain("api.lib.ntust.edu.tw", true)), emptyList(), emptySet())
        answers += { throw SSLHandshakeException("handshake failed").apply { initCause(PinMismatchException(mismatch)) } }

        controller.start(64, accounts)

        assertEquals(ACCOUNT_CHANGED, controller.error.value)
    }

    /**
     * Under the page's generic "Failed to generate QR code", a failure without
     * a message has no reason to give, and its class name is none: a release
     * build would show R8's short name for it.
     */
    @Test
    fun `a failed fetch without a message adds nothing to the generic error`() {
        answers += { throw IOException() }

        controller.start(64, accounts)

        assertEquals("", controller.error.value)
        assertNull(controller.qrBitmap.value)
    }

    @Test
    fun `a failure's own message is still the reason shown`() {
        assertEquals("Unable to resolve host", qrErrorDetail(IOException("Unable to resolve host")))
        assertEquals("", qrErrorDetail(IOException(" ")))
        assertEquals("", qrErrorDetail(IllegalStateException()))
    }

    @Test
    fun `another account from the phone starts the code afresh`() {
        answers += { throw LibraryServiceError.CredentialsNotFound() }
        answers += { "code" }
        controller.start(64, accounts)

        accounts.value = "B10000002"

        assertNull(controller.error.value)
        assertNotNull(controller.qrBitmap.value)
    }

    @Test
    fun `the previous account's code is gone while the next one's loads`() {
        answers += { "code" }
        answers += { awaitCancellation() }
        controller.start(64, accounts)
        assertNotNull(controller.qrBitmap.value)

        accounts.value = "B10000002"

        assertNull(controller.qrBitmap.value)
        assertTrue(controller.isLoading.value)
    }

    /**
     * The phone hands over another account while the previous one's code is being fetched again,
     * and that fetch does not stop at once: the previous code goes as the account changes, not
     * once the fetch is over, so it never stands beside the next student's ID.
     */
    @Test
    fun `another account takes the previous code off at once, while its fetch runs on`() {
        var held: Continuation<String>? = null
        answers += { "code" }
        answers += { withContext(NonCancellable) { suspendCoroutine { held = it } } }
        answers += { awaitCancellation() }
        controller.start(64, accounts)
        // A restart for the same account fetches again with its code still up.
        controller.start(128, accounts)
        assertNotNull(controller.qrBitmap.value)

        accounts.value = "B10000002"

        assertNull(controller.qrBitmap.value)
        held!!.resume("late")
    }

    @Test
    fun `the same account in another case keeps its code while it reloads`() {
        answers += { "code" }
        answers += { awaitCancellation() }
        controller.start(64, accounts)

        accounts.value = "b10000001"

        assertNotNull(controller.qrBitmap.value)
    }

    @Test
    fun `the same account with spaces around it keeps its code while it reloads`() {
        answers += { "code" }
        answers += { awaitCancellation() }
        controller.start(64, accounts)

        accounts.value = " b10000001 "

        assertNotNull(controller.qrBitmap.value)
    }

    /** Runs what is dispatched to it only when the test says, in order. */
    private class Queue : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    /**
     * A restart, as toggling full screen makes, while the previous code still
     * renders: the previous fetch's cleanup, once its render is done, must not
     * mark the new fetch as finished.
     */
    @Test
    fun `a restart while the previous code renders stays loading until its own code is in`() {
        val main = Queue()
        val render = Queue()
        val queued = CoroutineScope(SupervisorJob() + main)
        val controller = LibraryQRController({ answers.removeFirst()() }, queued, ACCOUNT_CHANGED, render)
        answers += { "code" }
        answers += { awaitCancellation() }

        controller.start(64, accounts)
        main.runAll()
        controller.start(128, accounts)
        main.runAll()
        render.runAll()
        main.runAll()

        assertTrue(controller.isLoading.value)
        queued.cancel()
    }

    private companion object {
        const val ACCOUNT_CHANGED = "Open TigerDuck on your phone to sync."
    }
}
