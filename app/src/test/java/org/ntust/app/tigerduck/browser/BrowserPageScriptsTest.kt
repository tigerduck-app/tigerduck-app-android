package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The style the browser adds to Moodle's pages so they can always be panned. */
class BrowserPageScriptsTest {

    @Test
    fun `the rule lets the document and the body pan, over any inline style`() {
        val script = BrowserPageScripts.TOUCH_PAN

        assertTrue(script, script.contains("html, body { touch-action: auto !important; }"))
        assertTrue("adds it as a stylesheet", script.contains("createElement('style')"))
        assertTrue("before the page has a head", script.contains("document.documentElement"))
    }

    @Test
    fun `only Moodle's pages get it, where YUI turns touch off`() {
        assertEquals(setOf("https://moodle2.ntust.edu.tw"), BrowserPageScripts.TOUCH_PAN_ORIGINS)
    }

    @Test
    fun `Moodle's logout links and forms are hidden, those drawn later too`() {
        val script = BrowserPageScripts.HIDE_LOGOUT

        assertTrue(script, script.contains("a[href^=\"https://moodle2.ntust.edu.tw/login/logout.php\"]"))
        assertTrue(script, script.contains("a[href^=\"https://moodle2.ntust.edu.tw/auth/oidc/logout.php\"]"))
        assertTrue(script, script.contains("form[action^=\"https://moodle2.ntust.edu.tw/login/logout.php\"]"))
        assertTrue(script, script.contains("a[href^=\"/login/logout.php\"]"))
        assertTrue(script, script.contains("display: none !important"))
        assertTrue("a stylesheet, which also matches the menus Moodle draws later", script.contains("createElement('style')"))
        assertTrue("before the page has a head", script.contains("document.documentElement"))
    }

    @Test
    fun `only Moodle's pages lose their logout`() {
        assertEquals(setOf("https://moodle2.ntust.edu.tw"), BrowserPageScripts.HIDE_LOGOUT_ORIGINS)
    }

    @Test
    fun `a link that only names the logout in its query stays`() {
        // Matched by where a link starts, not by anything inside it.
        assertFalse(BrowserPageScripts.HIDE_LOGOUT, BrowserPageScripts.HIDE_LOGOUT.contains("*=\"logout.php\""))
    }

    @Test
    fun `a WebView without has() still hides the logout, and tidies what it leaves by script`() {
        val script = BrowserPageScripts.HIDE_LOGOUT

        // Two rules: a browser that cannot read :has() drops only the second.
        assertTrue(script, script.contains("logout + ' { display: none !important; }\\n" + BrowserPageScripts.LEFT_OVER_SELECTOR))
        assertFalse(BrowserPageScripts.LOGOUT_SELECTOR, BrowserPageScripts.LOGOUT_SELECTOR.contains(":has("))
        assertTrue(script, script.contains("CSS.supports('selector(:has(*))')"))
        assertTrue("the divider right above", script.contains("previousElementSibling") && script.contains("dropdown-divider"))
        assertTrue("a link's logged-in line", script.contains("found.closest('.logininfo')"))
        assertTrue("a form's own button wrapper, not one further up", script.contains("found.parentElement"))
        assertTrue("as the page changes", script.contains("MutationObserver"))
    }

    @Test
    fun `a focus ring shows only for focus the keyboard moved, on Moodle's pages`() {
        val script = BrowserPageScripts.TOUCH_FOCUS

        listOf("button", "a", "input", "select", "textarea", "[tabindex]").forEach {
            assertTrue(it, script.contains("$it:focus:not(:focus-visible)"))
        }
        assertTrue(script, script.contains("outline: none !important; box-shadow: none !important;"))
        assertFalse("the keyboard's ring is left alone", script.replace(":not(:focus-visible)", "").contains(":focus-visible"))
        assertEquals(setOf("https://moodle2.ntust.edu.tw"), BrowserPageScripts.TOUCH_FOCUS_ORIGINS)
    }

    @Test
    fun `a school site's logout links and forms are found by the routing's own rule, and controls by their words`() {
        val script = BrowserPageScripts.HIDE_SCHOOL_LOGOUT

        assertTrue("reads each address as a URL", script.contains("new URL("))
        // The routing's words, script extensions and action parameters, not a copy of them.
        BrowserRouting.SCHOOL_LOGOUT_WORDS.forEach { assertTrue(it, script.contains("'$it'")) }
        BrowserRouting.SCRIPT_EXTENSIONS.forEach { assertTrue(it, script.contains("'$it'")) }
        BrowserRouting.ACTION_PARAMETERS.forEach { assertTrue(it, script.contains("'$it'")) }
        assertTrue("path parameters off", script.contains("split(';')"))
        assertTrue("by the words on them", script.contains("登出") && script.contains("sign"))
        assertTrue("as the page changes", script.contains("MutationObserver"))
        assertTrue("Moodle's go by its own rules", script.contains("moodle2.ntust.edu.tw"))
    }

    @Test
    fun `a school page's wrappers stay, as no rule hides an element by its class or id alone`() {
        val script = BrowserPageScripts.HIDE_SCHOOL_LOGOUT

        assertFalse(script, script.contains("[class*=\"logout\" i] {"))
        assertFalse(script, script.contains("display: none !important; }"))
        assertFalse("no address matched by a substring", script.contains("href*="))
    }

    @Test
    fun `every school page loses its logout`() {
        assertEquals(setOf("https://ntust.edu.tw", "https://*.ntust.edu.tw"), BrowserPageScripts.HIDE_SCHOOL_LOGOUT_ORIGINS)
    }
}
