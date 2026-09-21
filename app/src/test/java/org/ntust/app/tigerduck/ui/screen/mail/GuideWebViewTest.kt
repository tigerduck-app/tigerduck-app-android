package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideWebViewTest {

    @Test
    fun `accepts the help path, with or without a query, with or without a trailing slash`() {
        assertTrue(isGuideUrl("https://tigerduck.app/help/receive-mail/android"))
        assertTrue(isGuideUrl("https://tigerduck.app/help/receive-mail/android?embed=1&theme=dark"))
        assertTrue(isGuideUrl("https://tigerduck.app/help/receive-mail/apple/"))
    }

    @Test
    fun `rejects a lookalike host`() {
        assertFalse(isGuideUrl("https://evil-tigerduck.app/help/receive-mail/android"))
        assertFalse(isGuideUrl("https://tigerduck.app.evil.com/help/receive-mail/android"))
        assertFalse(isGuideUrl("https://not-tigerduck.app/help/receive-mail/android"))
    }

    @Test
    fun `rejects userinfo smuggling the real host in front of an attacker one`() {
        assertFalse(isGuideUrl("https://tigerduck.app@evil.com/help/receive-mail/android"))
    }

    @Test
    fun `rejects query smuggling the real path onto an attacker host`() {
        assertFalse(isGuideUrl("https://evil.com/?next=https://tigerduck.app/help/receive-mail/android"))
    }

    @Test
    fun `rejects a scheme-relative url`() {
        assertFalse(isGuideUrl("//tigerduck.app/help/receive-mail/android"))
    }

    @Test
    fun `rejects non-https schemes, including ones a WebView would happily execute`() {
        assertFalse(isGuideUrl("javascript:alert(1)"))
        assertFalse(isGuideUrl("data:text/html,<script>alert(1)</script>"))
        assertFalse(isGuideUrl("file:///etc/passwd"))
        assertFalse(isGuideUrl("http://tigerduck.app/help/receive-mail/android"))
    }

    @Test
    fun `rejects a path outside the help section, and a dot-dot escape from it`() {
        assertFalse(isGuideUrl("https://tigerduck.app/"))
        assertFalse(isGuideUrl("https://tigerduck.app/other"))
        assertFalse(isGuideUrl("https://tigerduck.app/help/receive-mail/../../other"))
    }

    @Test
    fun `an unparseable url is rejected, not thrown`() {
        assertFalse(isGuideUrl("not a url"))
    }
}
