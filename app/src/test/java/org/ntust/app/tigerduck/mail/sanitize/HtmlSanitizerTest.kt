package org.ntust.app.tigerduck.mail.sanitize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSanitizerTest {
    private val forbidden = listOf(
        "<script", "javascript:", "vbscript:", "data:text", "data:image/svg", "onerror", "onload", "onclick",
        "onmouseover", "<iframe", "<object", "<embed", "<svg", "<math", "<base", "<meta", "<form",
        "<input", "<style", "<link", "<template", "<noscript", "expression(", "url(", "@import",
        "behavior", "-moz-binding", "position", "z-index", "background=", "image-set",
    )

    @Test
    fun `nothing from the XSS corpus survives`() {
        val corpus = requireNotNull(javaClass.getResourceAsStream("/mail/xss-corpus.txt")).bufferedReader().readLines()
            .filter { it.isNotBlank() }
        assertTrue(corpus.size >= 30)
        for (vector in corpus) {
            val out = HtmlSanitizer.sanitize(vector, allowRemoteImages = false).html.lowercase()
            for (token in forbidden) {
                assertFalse("'$token' survived in: $vector -> $out", token in out)
            }
        }
    }

    @Test
    fun `formatting and safe styles are kept`() {
        val out = HtmlSanitizer.sanitize(
            "<p style=\"color: red; position: fixed\">Hi <b>there</b></p><table border=\"1\"><tr><td colspan=\"2\">x</td></tr></table>",
            allowRemoteImages = false,
        ).html
        assertTrue(out, "<b>there</b>" in out)
        assertTrue(out, "style=\"color: red\"" in out)
        assertTrue(out, "colspan=\"2\"" in out)
    }

    @Test
    fun `remote images are held back until allowed`() {
        val html = "<img src=\"https://track.example/p.gif\"><img src=\"cid:logo@x\"><img src=\"data:image/png;base64,AAAA\">"
        val blocked = HtmlSanitizer.sanitize(html, allowRemoteImages = false)
        assertEquals(1, blocked.blockedRemoteImages)
        assertEquals(setOf("https://track.example/p.gif"), blocked.remoteImageUrls)
        assertFalse(blocked.html, " src=\"https://track.example" in blocked.html)
        assertTrue(blocked.html, "data-remote-src=\"https://track.example/p.gif\"" in blocked.html)
        assertTrue(blocked.html, "src=\"cid:logo@x\"" in blocked.html)
        assertTrue(blocked.html, "src=\"data:image/png;base64,AAAA\"" in blocked.html)

        val allowed = HtmlSanitizer.sanitize(html, allowRemoteImages = true)
        assertEquals(0, allowed.blockedRemoteImages)
        assertTrue(allowed.html, "src=\"https://track.example/p.gif\"" in allowed.html)
    }

    @Test
    fun `sender-supplied data-remote-src is not trusted`() {
        val result = HtmlSanitizer.sanitize(
            "<img data-remote-src=\"https://track.example/hidden.gif\">",
            allowRemoteImages = false,
        )
        assertEquals(0, result.blockedRemoteImages)
        assertEquals(emptySet<String>(), result.remoteImageUrls)
        assertFalse(result.html, "data-remote-src" in result.html)
    }

    @Test
    fun `links are extracted with their visible text`() {
        val result = HtmlSanitizer.sanitize(
            "<a href=\"https://evil.example/login\">https://www.ntust.edu.tw</a> <a href=\"mailto:a@b.tw\">mail</a>",
            allowRemoteImages = false,
        )
        assertEquals(
            listOf(MailLink("https://www.ntust.edu.tw", "https://evil.example/login"), MailLink("mail", "mailto:a@b.tw")),
            result.links,
        )
    }

    @Test
    fun `plain text keeps block boundaries as line breaks`() {
        val clean = HtmlSanitizer.sanitize("<p>one</p><div>two<br>three</div>", allowRemoteImages = false).html
        assertEquals("one\ntwo\nthree", HtmlSanitizer.plainText(clean))
    }
}
