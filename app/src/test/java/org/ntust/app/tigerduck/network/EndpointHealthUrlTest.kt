package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/health` is a sibling of the version prefix, not a child of it. Getting
 * this wrong makes every save fail with "not a TigerDuck backend" against a
 * perfectly healthy server, which is worse than not probing at all.
 */
class EndpointHealthUrlTest {

    private fun health(base: String) = EndpointHealthCheck.healthUrl(base)?.toString()

    @Test
    fun `version prefix is replaced, not appended to`() {
        assertEquals("https://api.tigerduck.app/health", health("https://api.tigerduck.app/v3"))
    }

    @Test
    fun `trailing slash does not shift the segment that gets dropped`() {
        assertEquals("https://api.tigerduck.app/health", health("https://api.tigerduck.app/v3/"))
    }

    @Test
    fun `deployment behind a path prefix keeps the prefix`() {
        assertEquals(
            "https://example.com/tigerduck/health",
            health("https://example.com/tigerduck/v3"),
        )
    }

    @Test
    fun `bare origin resolves to its root health`() {
        assertEquals("https://example.com/health", health("https://example.com"))
    }

    @Test
    fun `lan backend keeps scheme port and host`() {
        assertEquals("http://192.168.1.5:40000/health", health("http://192.168.1.5:40000/v3"))
    }

    @Test
    fun `query and fragment are dropped`() {
        assertEquals("https://example.com/health", health("https://example.com/v3?x=1#y"))
    }

    @Test
    fun `unparseable base returns null`() {
        assertNull(health("not a url"))
    }
}
