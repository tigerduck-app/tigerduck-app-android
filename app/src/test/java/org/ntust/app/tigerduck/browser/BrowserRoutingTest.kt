package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a page's main-frame navigation does inside the TigerDuck browser. */
class BrowserRoutingTest {

    private val allowlist = BrowserAllowlist.DEFAULT

    @Test
    fun `an allowlisted https page loads inside`() {
        assertEquals(NavigationDecision.Load, BrowserRouting.navigation("https://moodle2.ntust.edu.tw/my/", allowlist))
    }

    @Test
    fun `any other web page asks to go outside, http on the school's hosts too`() {
        listOf("https://example.com/", "http://moodle2.ntust.edu.tw/", "https://ntust.edu.tw/").forEach {
            assertEquals(it, NavigationDecision.OpenOutside(it), BrowserRouting.navigation(it, allowlist))
        }
    }

    @Test
    fun `a mail link goes to the mail app`() {
        val mail = "mailto:someone@example.com"
        assertEquals(NavigationDecision.HandOff(mail), BrowserRouting.navigation(mail, allowlist))
    }

    @Test
    fun `about blank stays and every other scheme is refused`() {
        assertEquals(NavigationDecision.Load, BrowserRouting.navigation("about:blank", allowlist))
        listOf(
            "about:config", "intent://x#Intent;end", "javascript:alert(1)", "file:///sdcard/a",
            "content://x/y", "data:text/html,hi", "market://details?id=x", "tel:123", "nonsense",
        ).forEach { assertEquals(it, NavigationDecision.Refuse, BrowserRouting.navigation(it, allowlist)) }
    }

    @Test
    fun `allow every site loads any https page inside`() {
        val open = BrowserAllowlist(BrowserAllowlist.DEFAULT_ENTRIES, allowEverySite = true)
        assertEquals(NavigationDecision.Load, BrowserRouting.navigation("https://example.com/", open))
    }

    @Test
    fun `a main-frame request off the allowlist is stopped, a page's own resources are not`() {
        assertTrue(BrowserRouting.blocksRequest("https://example.com/collect", isForMainFrame = true, allowlist = allowlist))
        assertTrue(BrowserRouting.blocksRequest("http://moodle2.ntust.edu.tw/x", isForMainFrame = true, allowlist = allowlist))
        assertFalse(BrowserRouting.blocksRequest("https://moodle2.ntust.edu.tw/x", isForMainFrame = true, allowlist = allowlist))
        assertFalse(BrowserRouting.blocksRequest("https://cdn.example.com/a.js", isForMainFrame = false, allowlist = allowlist))
    }

    // --- Moodle's logout: the session stays until TigerDuck itself signs out ---

    private val logouts = listOf(
        "https://moodle2.ntust.edu.tw/login/logout.php",
        "https://moodle2.ntust.edu.tw/login/logout.php?sesskey=abc123",
        "https://moodle2.ntust.edu.tw/login/logout.php#top",
        "https://MOODLE2.ntust.edu.tw/login/logout.php?sesskey=abc123",
        "https://moodle2.ntust.edu.tw./login/logout.php",
        "https://moodle2.ntust.edu.tw:443/login/logout.php",
        "http://moodle2.ntust.edu.tw/login/logout.php",
        "https://moodle2.ntust.edu.tw//login//logout.php",
        "https://moodle2.ntust.edu.tw/login/./logout.php",
        "https://moodle2.ntust.edu.tw/course/../login/logout.php",
        "https://moodle2.ntust.edu.tw/login/logout%2Ephp",
        "https://moodle2.ntust.edu.tw/LOGIN/LOGOUT.PHP",
        "https://moodle2.ntust.edu.tw/login/logout.php/",
        "https://moodle2.ntust.edu.tw/login/logout.php/anything",
        "https://moodle2.ntust.edu.tw/auth/oidc/logout.php",
        "https://moodle2.ntust.edu.tw/auth/oidc/logout.php?sid=x",
    )

    @Test
    fun `Moodle's logout is refused in every form its address can take`() {
        val everySite = BrowserAllowlist(BrowserAllowlist.DEFAULT_ENTRIES, allowEverySite = true)
        logouts.forEach {
            assertEquals(it, NavigationDecision.Refuse, BrowserRouting.navigation(it, allowlist))
            assertEquals(it, NavigationDecision.Refuse, BrowserRouting.navigation(it, everySite))
        }
    }

    @Test
    fun `a request to Moodle's logout never goes, from the page or from a script`() {
        logouts.forEach {
            assertTrue(it, BrowserRouting.blocksRequest(it, isForMainFrame = true, allowlist = allowlist))
            assertTrue(it, BrowserRouting.blocksRequest(it, isForMainFrame = false, allowlist = allowlist))
        }
    }

    @Test
    fun `pages that only name the logout still load`() {
        listOf(
            "https://moodle2.ntust.edu.tw/login/index.php",
            "https://moodle2.ntust.edu.tw/login/logoutx.php",
            "https://moodle2.ntust.edu.tw/course/view.php?id=5&return=/login/logout.php",
            "https://moodle2.ntust.edu.tw/mod/page/view.php?id=1#/login/logout.php",
            "https://moodle2.ntust.edu.tw/blog/login/logout.php",
            "https://sso.ntust.edu.tw/login/logout.php",
        ).forEach {
            assertEquals(it, NavigationDecision.Load, BrowserRouting.navigation(it, allowlist))
            assertFalse(it, BrowserRouting.blocksRequest(it, isForMainFrame = false, allowlist = allowlist))
        }
    }
}
