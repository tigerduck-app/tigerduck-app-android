package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a browser opened at risk lets past a certificate error: that host and that leaf only (tigerduck-app#958). */
class BrowserRiskAcceptanceTest {

    private val leaf = "a".repeat(64)
    private val otherLeaf = "b".repeat(64)
    private val refusal = CertificateRefusal("https://suggestionbox.ntust.edu.tw/login?next=1", leaf)
    private val accepted = BrowserRiskAcceptance().accepting(refusal)

    @Test
    fun `the refused host's leaf is let past, on any of its pages`() {
        assertTrue(accepted.allows("https://suggestionbox.ntust.edu.tw/login?next=1", leaf))
        assertTrue(accepted.allows("https://suggestionbox.ntust.edu.tw/static/app.js", leaf))
        assertTrue("its host as the pin matcher names it", accepted.allows("https://SUGGESTIONBOX.ntust.edu.tw./", leaf))
    }

    @Test
    fun `another leaf on that host is not`() {
        assertFalse(accepted.allows("https://suggestionbox.ntust.edu.tw/login", otherLeaf))
        assertFalse(accepted.allows("https://suggestionbox.ntust.edu.tw/login", null))
    }

    @Test
    fun `that leaf on another host is not, a host the app signs in to or a subdomain alike`() {
        assertFalse(accepted.allows("https://moodle2.ntust.edu.tw/", leaf))
        assertFalse(accepted.allows("https://ssoam2.ntust.edu.tw/", leaf))
        assertFalse(accepted.allows("https://a.suggestionbox.ntust.edu.tw/", leaf))
        assertFalse(accepted.allows("https://ntust.edu.tw/", leaf))
        assertFalse(accepted.allows(null, leaf))
    }

    @Test
    fun `a browser that accepted nothing lets nothing past`() {
        assertFalse(BrowserRiskAcceptance().allows("https://suggestionbox.ntust.edu.tw/login", leaf))
    }

    @Test
    fun `each later acceptance adds its own, and keeps those before`() {
        val both = accepted.accepting(CertificateRefusal("https://moodle2.ntust.edu.tw/course/view.php?id=1", otherLeaf))

        assertTrue(both.allows("https://suggestionbox.ntust.edu.tw/", leaf))
        assertTrue(both.allows("https://moodle2.ntust.edu.tw/", otherLeaf))
        assertFalse(both.allows("https://moodle2.ntust.edu.tw/", leaf))
    }

    @Test
    fun `it goes through an intent as it was`() {
        val decoded = BrowserRiskAcceptance.decode(accepted.encoded())!!

        assertEquals(listOf("suggestionbox.ntust.edu.tw $leaf"), accepted.encoded())
        assertTrue(decoded.allows("https://suggestionbox.ntust.edu.tw/", leaf))
        assertFalse(decoded.allows("https://suggestionbox.ntust.edu.tw/", otherLeaf))
    }

    @Test
    fun `no extra is no browser opened at risk, and a line that does not read is left out`() {
        assertNull(BrowserRiskAcceptance.decode(null))
        val decoded = BrowserRiskAcceptance.decode(
            listOf("", "suggestionbox.ntust.edu.tw", "suggestionbox.ntust.edu.tw short", "MOODLE2.ntust.edu.tw $leaf", "moodle2.ntust.edu.tw. $leaf", " $leaf"),
        )!!
        assertEquals(emptyList<String>(), decoded.encoded())
    }
}
