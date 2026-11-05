package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.shared.account.SignInSeries

class AccountSessionGuardTest {

    private val series = SignInSeries()
    private val guard = AccountSessionGuard(AccountSession(series, CoroutineScope(SupervisorJob())))

    private fun request() = Request.Builder().url("https://ssoam2.ntust.edu.tw/").build()

    private fun responseTo(request: Request) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Set-Cookie", "session=departing")
        .header("Content-Type", "text/html")
        .build()

    @Test
    fun `a call started under the signed-in account goes through`() {
        val call = guard.marked(request())
        assertFalse(guard.isStale(call))
        assertEquals("session=departing", guard.withoutStaleCookies(call, responseTo(call)).header("Set-Cookie"))
    }

    @Test
    fun `a call started under an ended sign-in is refused its next hop and keeps its cookies out`() {
        val call = guard.marked(request())
        series.end()

        assertTrue(guard.isStale(call))
        val response = guard.withoutStaleCookies(call, responseTo(call))
        assertEquals(null, response.header("Set-Cookie"))
        assertEquals("text/html", response.header("Content-Type"))
    }

    @Test
    fun `a redirect hop keeps the sign-in its call started under`() {
        val call = guard.marked(request())
        series.end()
        series.open()

        // Follow-ups and the bridge's rewrite are built with newBuilder().
        val hop = guard.marked(call.newBuilder().url("https://courseselection.ntust.edu.tw/").build())
        assertTrue(guard.isStale(hop))
    }

    @Test
    fun `a call started after the sign-out belongs to the next sign-in`() {
        series.end()
        series.open()
        assertFalse(guard.isStale(guard.marked(request())))
    }

    @Test
    fun `a call started while the sign-out clears is the account's that left`() {
        series.end()
        assertTrue("the session is closed: no one's request goes through", guard.isStale(guard.marked(request())))
    }

    @Test
    fun `the same student signing back in does not bring a call back`() {
        val call = guard.marked(request())
        series.end()
        series.open()
        assertTrue(guard.isStale(call))
    }

    @Test
    fun `an unmarked request is never refused`() {
        series.end()
        assertFalse(guard.isStale(request()))
    }
}
