package org.ntust.app.tigerduck.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FetchWithSsoSessionTest {

    private class Bounced : Exception()

    /** A service that bounces the first [bounces] requests to SSO. */
    private class Service(private var bounces: Int) {
        var logins = 0
        var requests = 0

        fun logIn() {
            logins++
        }

        fun fetch(): String? {
            requests++
            return if (bounces-- > 0) null else "page"
        }
    }

    private suspend fun Service.fetch(sessionWarm: Boolean): String = fetchWithSsoSession(
        sessionWarm = sessionWarm,
        logIn = { logIn() },
        fetch = { fetch() },
        bounced = { throw Bounced() },
    )

    @Test
    fun `a warm session fetches the page without logging in`() = runTest {
        val service = Service(bounces = 0)
        assertEquals("page", service.fetch(sessionWarm = true))
        assertEquals(0, service.logins)
        assertEquals(1, service.requests)
    }

    @Test
    fun `a cold session logs in before the fetch`() = runTest {
        val service = Service(bounces = 0)
        assertEquals("page", service.fetch(sessionWarm = false))
        assertEquals(1, service.logins)
        assertEquals(1, service.requests)
    }

    @Test
    fun `a warm session that is bounced logs in once and retries`() = runTest {
        val service = Service(bounces = 1)
        assertEquals("page", service.fetch(sessionWarm = true))
        assertEquals(1, service.logins)
        assertEquals(2, service.requests)
    }

    @Test
    fun `a bounce that survives the login gives up after one retry`() {
        val service = Service(bounces = 2)
        assertThrows(Bounced::class.java) {
            kotlinx.coroutines.runBlocking { service.fetch(sessionWarm = true) }
        }
        assertEquals(1, service.logins)
        assertEquals(2, service.requests)
    }

    @Test
    fun `a cold session that is bounced after its login gives up without a second login`() {
        val service = Service(bounces = 1)
        assertThrows(Bounced::class.java) {
            kotlinx.coroutines.runBlocking { service.fetch(sessionWarm = false) }
        }
        assertEquals(1, service.logins)
        assertEquals(1, service.requests)
    }

    @Test
    fun `a failed login is not followed by a fetch`() {
        class LoginFailed : Exception()
        var requests = 0
        assertThrows(LoginFailed::class.java) {
            kotlinx.coroutines.runBlocking {
                fetchWithSsoSession<String>(
                    sessionWarm = false,
                    logIn = { throw LoginFailed() },
                    fetch = { requests++; "page" },
                    bounced = { throw Bounced() },
                )
            }
        }
        assertEquals(0, requests)
    }
}
