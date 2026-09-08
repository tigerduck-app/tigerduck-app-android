package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpoint gate lets anyone point the app at their own backend, so the
 * only thing standing between a Bearer token and the open wire is the
 * "public addresses must be HTTPS" rule. These pin both halves: what counts
 * as private (and so may use cleartext), and what does not.
 */
class OverrideValidatorTest {

    private fun ok(raw: String): OverrideValidator.Result.Ok =
        OverrideValidator.validate(raw) as OverrideValidator.Result.Ok

    // --- Transport rule ---

    @Test
    fun `public host over https is accepted`() {
        assertEquals("https://api.example.com/v3", ok("https://api.example.com/v3").normalized)
    }

    @Test
    fun `arbitrary self-hosted domain is accepted - there is no allowlist`() {
        assertEquals("https://tigerduck.my-nas.net/v3", ok("https://tigerduck.my-nas.net/v3").normalized)
    }

    @Test
    fun `public host over http is rejected`() {
        assertEquals(
            OverrideValidator.Result.Insecure,
            OverrideValidator.validate("http://api.example.com/v3"),
        )
    }

    @Test
    fun `public ip literal over http is rejected`() {
        assertEquals(
            OverrideValidator.Result.Insecure,
            OverrideValidator.validate("http://8.8.8.8:40000/v3"),
        )
    }

    @Test
    fun `private host over http is accepted`() {
        assertEquals("http://192.168.1.5:40000/v3", ok("http://192.168.1.5:40000/v3").normalized)
    }

    @Test
    fun `https to a private host is rewritten to http`() {
        val result = ok("https://192.168.1.5:40000/v3")
        assertEquals("http://192.168.1.5:40000/v3", result.normalized)
        assertTrue(result.rewrittenToHttp)
    }

    @Test
    fun `scheme rewrite preserves percent-escapes in the path`() {
        // The 7-arg URI constructor re-encodes decoded components, which
        // double-escapes anything the user pasted. String-level swap must not.
        assertEquals("http://10.0.0.2/v3/a%2Fb", ok("https://10.0.0.2/v3/a%2Fb").normalized)
    }

    // --- Malformed input ---

    @Test
    fun `missing scheme is malformed`() {
        assertEquals(OverrideValidator.Result.Malformed, OverrideValidator.validate("api.example.com/v3"))
    }

    @Test
    fun `non-http scheme is malformed`() {
        assertEquals(OverrideValidator.Result.Malformed, OverrideValidator.validate("ftp://example.com"))
    }

    @Test
    fun `out-of-range port is malformed`() {
        // URI accepts it; OkHttp would throw at every request afterwards.
        assertEquals(OverrideValidator.Result.Malformed, OverrideValidator.validate("https://example.com:99999/v3"))
    }

    // --- IPv4 classification ---

    @Test
    fun `rfc1918 loopback and link-local are private`() {
        listOf(
            "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1",
            "127.0.0.1", "127.1.2.3", "169.254.10.1", "localhost", "db.localhost",
        ).forEach {
            assertTrue(it, OverrideValidator.isPrivateOrLoopbackHost(it))
        }
    }

    @Test
    fun `routable v4 ranges are not private`() {
        // 172.32 is outside the /12; 100.64 is CGNAT, which the carrier routes.
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.64.0.1", "193.168.0.1")
            .forEach { assertFalse(it, OverrideValidator.isPrivateOrLoopbackHost(it)) }
    }

    @Test
    fun `leading-zero octets are rejected rather than read as octal`() {
        // Some resolvers read 0192 as octal and land somewhere else entirely,
        // so this must not classify as private.
        assertFalse(OverrideValidator.isPrivateOrLoopbackHost("0192.168.1.5"))
    }

    // --- IPv6 classification ---

    @Test
    fun `ipv6 loopback unique-local and link-local are private`() {
        listOf(
            "::1", "[::1]", "fd00::1", "fc00::1", "fe80::1", "fe80::1%wlan0",
            "[fd12:3456:789a::1]",
        ).forEach { assertTrue(it, OverrideValidator.isPrivateOrLoopbackHost(it)) }
    }

    @Test
    fun `routable ipv6 is not private`() {
        listOf("2001:db8::1", "[2606:4700:4700::1111]", "fec0::1")
            .forEach { assertFalse(it, OverrideValidator.isPrivateOrLoopbackHost(it)) }
    }

    @Test
    fun `ipv4-mapped ipv6 is judged by the embedded address`() {
        assertTrue(OverrideValidator.isPrivateOrLoopbackHost("::ffff:192.168.1.5"))
        assertFalse(OverrideValidator.isPrivateOrLoopbackHost("::ffff:8.8.8.8"))
    }

    @Test
    fun `bracketed ipv6 literal over http is accepted`() {
        assertEquals("http://[fd00::1]:40000/v3", ok("http://[fd00::1]:40000/v3").normalized)
    }

    @Test
    fun `public ipv6 literal over http is rejected`() {
        assertEquals(
            OverrideValidator.Result.Insecure,
            OverrideValidator.validate("http://[2001:db8::1]:40000/v3"),
        )
    }
}
