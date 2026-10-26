package org.ntust.app.tigerduck.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionGuardTest {

    private val guard = AccountSessionGuard()

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
    fun `a call started before a sign-out is refused and keeps its cookies out`() {
        val call = guard.marked(request())
        guard.nextAccount()

        assertTrue(guard.isStale(call))
        val response = guard.withoutStaleCookies(call, responseTo(call))
        assertEquals(null, response.header("Set-Cookie"))
        assertEquals("text/html", response.header("Content-Type"))
    }

    @Test
    fun `a redirect hop keeps the account its call started under`() {
        val call = guard.marked(request())
        guard.nextAccount()

        // Follow-ups and the bridge's rewrite are built with newBuilder().
        val hop = guard.marked(call.newBuilder().url("https://courseselection.ntust.edu.tw/").build())
        assertTrue(guard.isStale(hop))
    }

    @Test
    fun `a call started after the sign-out belongs to the next account`() {
        guard.nextAccount()
        assertFalse(guard.isStale(guard.marked(request())))
    }

    @Test
    fun `an unmarked request is never refused`() {
        guard.nextAccount()
        assertFalse(guard.isStale(request()))
    }
}
