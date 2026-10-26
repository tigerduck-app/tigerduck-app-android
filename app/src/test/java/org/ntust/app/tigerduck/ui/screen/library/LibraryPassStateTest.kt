package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.library.LibraryQRCache
import org.ntust.app.tigerduck.shared.LibraryServiceError
import java.io.IOException

/**
 * The Library page's pass, as the view model runs it: the code cycle, the
 * page's own failures and the drop to the sign-in form when the shared
 * sign-in state turns signed out (tigerduck-app/tigerduck-app#556).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPassStateTest {

    /** Whether the stores still hold an account the page can show the pass for. */
    private var hasAccount = true

    /** The account a code is for, as [LibraryPageAccount.codeAccount] counts it. */
    private var account = 1

    /** The library sign-in state Settings and the flip follow. */
    private val signedIn = MutableStateFlow(true)

    /** Whether a sign-in typed on the page is under way. */
    private val signingIn = MutableStateFlow(false)

    /** What each code request does; past the end the library answers codes. */
    private val fetches = ArrayDeque<suspend () -> String>()
    private var fetched = 0

    /** Whether the page showed the pass each time it read the session again. */
    private val refreshedWhileLoggedIn = mutableListOf<Boolean>()

    /** The codes the page dated its "Last synced" row by, one per call. */
    private var dated = 0

    /** When the last code landed, as the view model keeps it across restarts. */
    private val fetchedAt = MutableStateFlow(0L)

    private lateinit var state: LibraryPassState<String>

    private fun TestScope.pass(): LibraryPassState<String> = LibraryPassState(
        scope = backgroundScope,
        hasAccount = { hasAccount },
        codeAccount = { account },
        fetch = {
            fetched++
            fetches.removeFirstOrNull()?.invoke() ?: "code-$fetched"
        },
        render = { "image of $it" },
        signedIn = signedIn,
        signingIn = signingIn,
        refreshSession = {
            refreshedWhileLoggedIn += state.isLoggedIn.value
            signedIn.value = hasAccount
        },
        describe = { it.message ?: "failed" },
        cache = LibraryQRCache(),
        now = { testScheduler.currentTime },
        onFetched = {
            dated++
            fetchedAt.value = EPOCH + testScheduler.currentTime
        },
        fetchedAt = fetchedAt,
    ).also { state = it }

    /**
     * The page's own code request finds the refusal: the sign-in it waits on records it, and
     * the app reads the session again, before the page hears of the failure. The page still
     * says what the library said.
     */
    @Test
    fun `the page's own refusal keeps the library's words when the sign-out it causes arrives first`() = runTest {
        fetches += {
            hasAccount = false
            signedIn.value = false
            yield()
            throw LibraryServiceError.LoginFailed("wrong password")
        }
        val pass = pass()

        pass.load()
        runCurrent()

        assertFalse(pass.isLoggedIn.value)
        assertEquals("wrong password", pass.errors.value)
        assertNull(pass.image.value)
    }

    /** It shows the sign-in form before it reads the session again, so that read drops nothing more. */
    @Test
    fun `the page's own refusal shows the form, then reads the session again`() = runTest {
        fetches += {
            hasAccount = false
            throw LibraryServiceError.LoginFailed("wrong password")
        }
        val pass = pass()

        pass.load()
        runCurrent()

        assertEquals(listOf(false), refreshedWhileLoggedIn)
        assertEquals("wrong password", pass.errors.value)
    }

    @Test
    fun `a sign-out found elsewhere drops the page while it backs off, and its old error with it`() = runTest {
        fetches += { throw IOException("timed out") }
        val pass = pass()
        pass.load()
        runCurrent()
        assertTrue(pass.isLoggedIn.value)
        assertEquals("timed out", pass.errors.value)

        hasAccount = false
        signedIn.value = false
        runCurrent()

        assertFalse(pass.isLoggedIn.value)
        assertNull(pass.errors.value)
        assertFalse(pass.waitingToRetry.value)
        pass.retryNow()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, fetched)
    }

    /**
     * The phone's retry signs in with a stored password the library had refused, once its wait is
     * over: a page left on the sign-in form shows the pass, as on the iPhone
     * (tigerduck-app/tigerduck-app#769).
     */
    @Test
    fun `a sign-in the app made by itself shows the pass on a page left on the sign-in form`() = runTest {
        hasAccount = false
        signedIn.value = false
        val pass = pass()
        pass.load()
        pass.resume()
        runCurrent()
        assertFalse(pass.isLoggedIn.value)

        hasAccount = true
        signedIn.value = true
        runCurrent()

        assertTrue(pass.isLoggedIn.value)
        assertEquals("image of code-1", pass.image.value)
        assertNull(pass.errors.value)
    }

    @Test
    fun `a sign-in made by itself while the page is away shows the pass when it comes back`() = runTest {
        hasAccount = false
        signedIn.value = false
        val pass = pass()
        pass.load()
        pass.resume()
        pass.pause()
        runCurrent()

        hasAccount = true
        signedIn.value = true
        runCurrent()
        assertFalse(pass.isLoggedIn.value)
        assertEquals(0, fetched)

        pass.resume()
        runCurrent()
        assertTrue(pass.isLoggedIn.value)
        assertEquals(1, fetched)
    }

    @Test
    fun `a sign-in typed on the page switches it once, by itself`() = runTest {
        hasAccount = false
        signedIn.value = false
        val pass = pass()
        pass.load()
        pass.resume()
        runCurrent()

        signingIn.value = true
        hasAccount = true
        signedIn.value = true
        runCurrent()
        assertFalse(pass.isLoggedIn.value)
        assertEquals(0, fetched)

        pass.signedIn()
        signingIn.value = false
        runCurrent()
        assertTrue(pass.isLoggedIn.value)
        assertEquals(1, fetched)
    }

    @Test
    fun `a sign-in made by itself while one typed on the page fails shows the pass once that ends`() = runTest {
        hasAccount = false
        signedIn.value = false
        val pass = pass()
        pass.load()
        pass.resume()
        runCurrent()

        signingIn.value = true
        hasAccount = true
        signedIn.value = true
        runCurrent()
        assertFalse(pass.isLoggedIn.value)

        // The typed one failed: the page's form says so, but the account the app signed in stands.
        pass.errors.value = "wrong password"
        signingIn.value = false
        runCurrent()

        assertTrue(pass.isLoggedIn.value)
        assertEquals(1, fetched)
        assertNull(pass.errors.value)
    }

    /** Retry on the page's error banner asks for a code at once (tigerduck-app/tigerduck-app#556). */
    @Test
    fun `Retry on the error banner asks for a code at once`() = runTest {
        fetches += { throw IOException("HTTP 503") }
        val pass = pass()
        pass.load()
        runCurrent()
        assertTrue(pass.waitingToRetry.value)

        pass.retryNow()
        runCurrent()

        assertEquals(2, fetched)
        assertEquals("image of code-2", pass.image.value)
        assertNull(pass.errors.value)
        assertFalse(pass.waitingToRetry.value)
    }

    /** The form's own failure, such as a password typed wrong, stays when the session reads signed out. */
    @Test
    fun `a page already on the sign-in form keeps what it says`() = runTest {
        hasAccount = false
        val pass = pass()
        pass.load()
        runCurrent()
        pass.errors.value = "wrong password"

        signedIn.value = false
        runCurrent()

        assertEquals("wrong password", pass.errors.value)
        assertEquals(0, fetched)
    }

    @Test
    fun `a signed-out the stores no longer say drops nothing`() = runTest {
        val pass = pass()
        pass.load()
        runCurrent()

        signedIn.value = false
        runCurrent()

        assertTrue(pass.isLoggedIn.value)
        assertEquals("image of code-1", pass.image.value)
    }

    /**
     * A sign-out elsewhere while a code request hangs, which the library client lets run until
     * its timeouts, drops the page after a bounded wait rather than when the request ends.
     */
    @Test
    fun `a sign-out elsewhere while a code request hangs drops the page after a bounded wait`() = runTest {
        fetches += { awaitCancellation() }
        val pass = pass()
        pass.load()
        runCurrent()

        hasAccount = false
        signedIn.value = false
        advanceTimeBy(LibraryPassState.SIGN_OUT_WAIT_MS - 1)
        runCurrent()
        assertTrue(pass.isLoggedIn.value)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(pass.isLoggedIn.value)
        assertNull(pass.errors.value)
    }

    /**
     * The account signs out elsewhere and another signs in while a code request is out: the
     * code that request brings is the previous account's, the previous code is too, and the
     * page goes on with a code of the account signed in now.
     */
    @Test
    fun `another account signed in while a code request is out gets its own code`() = runTest {
        val answer = CompletableDeferred<String>()
        val pass = pass()
        pass.load()
        runCurrent()
        assertEquals("image of code-1", pass.image.value)
        fetches += { answer.await() }
        advanceTimeBy(30_000)
        runCurrent()

        hasAccount = false
        signedIn.value = false
        runCurrent()
        account = 2
        hasAccount = true
        signedIn.value = true
        answer.complete("previous account's code")
        runCurrent()

        assertTrue(pass.isLoggedIn.value)
        assertEquals("image of code-3", pass.image.value)
        assertEquals(30, pass.countdown.value)
    }

    /**
     * The page's "Last synced" row is the last code that landed for the account on screen: a
     * failed request leaves it, and so does a code that came back for an account that left.
     */
    @Test
    fun `each code that lands dates the page, and a failed one or another account's does not`() = runTest {
        val pass = pass()
        pass.load()
        runCurrent()
        assertEquals("image of code-1", pass.image.value)
        assertEquals(1, dated)

        fetches += { throw IOException("HTTP 503") }
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, dated)

        // The retry comes back for an account that has since been replaced, and the page
        // fetches the new account's own code at once.
        fetches += {
            account = 2
            "previous account's code"
        }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("image of code-4", pass.image.value)
        assertEquals(2, dated)
    }

    /**
     * The page's "Last synced" row dates the code on screen, as on the iPhone. With none -- before
     * the first lands, after an expired one has gone, or signed out -- it says never, whatever
     * date a code from an earlier visit left behind.
     */
    @Test
    fun `the date shows only while a code is on screen`() = runTest {
        fetchedAt.value = 5_000L
        val pass = pass()
        runCurrent()
        assertNull(pass.image.value)
        assertEquals(0L, pass.syncedAt.value)

        pass.load()
        runCurrent()
        assertEquals("image of code-1", pass.image.value)
        assertEquals(EPOCH, pass.syncedAt.value)

        // A failed request at the code's end takes the expired code off the screen, and the
        // date with it; the retry a minute on puts a new code up, dated afresh.
        fetches += { throw IOException("HTTP 503") }
        advanceTimeBy(30_000)
        runCurrent()
        assertNull(pass.image.value)
        assertEquals(0L, pass.syncedAt.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("image of code-3", pass.image.value)
        assertEquals(EPOCH + 90_000, pass.syncedAt.value)

        // Signed out elsewhere: the code goes, and the row says never again.
        hasAccount = false
        signedIn.value = false
        runCurrent()
        assertNull(pass.image.value)
        assertEquals(0L, pass.syncedAt.value)
    }

    private companion object {
        /** The wall clock the dates are on, which the test scheduler's starts well before. */
        const val EPOCH = 1_700_000_000_000L
    }
}
