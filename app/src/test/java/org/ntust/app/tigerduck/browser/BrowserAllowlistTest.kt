package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pages the TigerDuck browser loads inside. */
class BrowserAllowlistTest {

    private fun list(vararg lines: String, allowEverySite: Boolean = false) =
        BrowserAllowlist(lines.map { AllowlistEntry.parse(it)!! }, allowEverySite)

    @Test
    fun `an exact entry lets in only its own host`() {
        val allowlist = list("moodle2.ntust.edu.tw")

        assertTrue(allowlist.allows("https://moodle2.ntust.edu.tw/user/edit.php"))
        assertFalse(allowlist.allows("https://ntust.edu.tw/"))
        assertFalse(allowlist.allows("https://www.moodle2.ntust.edu.tw/"))
        assertFalse(allowlist.allows("https://moodle2.ntust.edu.tw.example.com/"))
    }

    @Test
    fun `a wildcard lets in every subdomain at any depth but not the bare domain`() {
        val allowlist = list("*.ntust.edu.tw")

        assertTrue(allowlist.allows("https://moodle2.ntust.edu.tw/"))
        assertTrue(allowlist.allows("https://a.b.ntust.edu.tw/x"))
        assertFalse(allowlist.allows("https://ntust.edu.tw/"))
        assertFalse(allowlist.allows("https://evilntust.edu.tw/"))
        assertFalse(allowlist.allows("https://ntust.edu.tw.example.com/"))
    }

    @Test
    fun `only https on the default port with no user in the address`() {
        val allowlist = list("*.ntust.edu.tw")

        assertFalse(allowlist.allows("http://moodle2.ntust.edu.tw/"))
        assertFalse(allowlist.allows("https://moodle2.ntust.edu.tw:8443/"))
        assertFalse(allowlist.allows("https://someone@moodle2.ntust.edu.tw/"))
        assertFalse(allowlist.allows("ftp://moodle2.ntust.edu.tw/"))
        assertFalse(allowlist.allows("not a url"))
    }

    @Test
    fun `hosts compare without case`() {
        assertTrue(list("*.NTUST.edu.tw").allows("https://Moodle2.ntust.EDU.tw/"))
    }

    @Test
    fun `allow every site lets in any https page and still no other scheme`() {
        val allowlist = list("*.ntust.edu.tw", allowEverySite = true)

        assertTrue(allowlist.allows("https://example.com/"))
        assertFalse(allowlist.allows("http://example.com/"))
    }

    @Test
    fun `the default is the school's sites alone`() {
        assertTrue(BrowserAllowlist.DEFAULT.allows("https://moodle2.ntust.edu.tw/"))
        assertTrue(BrowserAllowlist.DEFAULT.allows("https://ssoam2.ntust.edu.tw/"))
        assertFalse(BrowserAllowlist.DEFAULT.allows("https://example.com/"))
        assertFalse(BrowserAllowlist.DEFAULT.allowEverySite)
    }

    @Test
    fun `an exact host a wildcard already covers is redundant`() {
        val allowlist = list("*.ntust.edu.tw", "moodle2.ntust.edu.tw", "example.com")

        assertTrue(allowlist.isRedundant(AllowlistEntry.Exact("moodle2.ntust.edu.tw")))
        assertFalse(allowlist.isRedundant(AllowlistEntry.Exact("example.com")))
        assertFalse(allowlist.isRedundant(AllowlistEntry.Wildcard("ntust.edu.tw")))
        // The bare domain is not under its own wildcard.
        assertFalse(allowlist.isRedundant(AllowlistEntry.Exact("ntust.edu.tw")))
    }

    @Test
    fun `parse takes a host or a wildcard and refuses anything else`() {
        assertEquals(AllowlistEntry.Exact("example.com"), AllowlistEntry.parse("  Example.COM "))
        assertEquals(AllowlistEntry.Wildcard("ntust.edu.tw"), AllowlistEntry.parse("*.ntust.edu.tw"))
        assertEquals("*.ntust.edu.tw", AllowlistEntry.parse("*.ntust.edu.tw")!!.text)

        listOf(
            "", "*", "*.tw", "https://example.com", "example.com/path", "example.com:443",
            "ex*ample.com", "a.*.example.com", "-bad.example.com", "exa mple.com", "example..com", ".example.com",
        ).forEach { assertNull(it, AllowlistEntry.parse(it)) }
    }
}
