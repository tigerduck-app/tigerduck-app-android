package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.InlineImage

class MailHtmlDocumentTest {
    private val png = InlineImage("image/png", byteArrayOf(1, 2, 3))

    @Test
    fun `cid images come from fetched parts and unknown ones are dropped`() {
        val html = """<p><img src="cid:logo@x"><img src="cid:missing@x"></p>"""
        val out = MailHtmlDocument.inlineCids(html, mapOf("<logo@x>" to png))
        assertTrue(out.contains("""src="data:image/png;base64,AQID""""))
        assertFalse(out.contains("cid:"))
    }

    @Test
    fun `percent-encoded cids still match`() {
        val out = MailHtmlDocument.inlineCids("""<img src="cid:a%40b">""", mapOf("<a@b>" to png))
        assertEquals("""<img src="data:image/png;base64,AQID">""", out)
    }

    @Test
    fun `non-image parts never become data URIs`() {
        val out = MailHtmlDocument.inlineCids("""<img src="cid:a">""", mapOf("a" to InlineImage("text/html", "<script>".toByteArray())))
        assertEquals("""<img src="">""", out)
    }

    @Test
    fun `the CSP allows remote images only after the user asks`() {
        assertEquals("default-src 'none'; img-src data:; style-src 'unsafe-inline'", MailHtmlDocument.csp(false))
        val doc = MailHtmlDocument.build("<p>x</p>", emptyMap(), allowRemoteImages = true)
        assertTrue(doc.contains("img-src data: https: http:"))
        assertTrue(doc.contains("<body><p>x</p></body>"))
    }

    // --- rewriteLinks: every <a href> becomes its index into SanitizedHtml.links -------------

    @Test
    fun `rewriteLinks replaces every href with its document-order index and keeps none of the originals`() {
        val html = """<p><a href="https://a.example">A</a> <a href="https://b.example/x?y=1">B</a></p>"""
        val out = MailHtmlDocument.rewriteLinks(html)
        assertTrue(out.contains("""href="https://link.invalid/0""""))
        assertTrue(out.contains("""href="https://link.invalid/1""""))
        assertFalse(out.contains("a.example"))
        assertFalse(out.contains("b.example"))
    }

    @Test
    fun `rewriteLinks reindexes duplicate hrefs to their own separate indices`() {
        val html = """<p><a href="https://ntust.edu.tw">first</a> <a href="https://ntust.edu.tw">second</a></p>"""
        val out = MailHtmlDocument.rewriteLinks(html)
        assertTrue(out.contains("""href="https://link.invalid/0""""))
        assertTrue(out.contains("""href="https://link.invalid/1""""))
        assertFalse(out.contains("ntust.edu.tw"))
    }

    @Test
    fun `rewriteLinks leaves html with no links unchanged`() {
        assertEquals("<p>no links here</p>", MailHtmlDocument.rewriteLinks("<p>no links here</p>"))
    }

    @Test
    fun `build's document never contains an original href, only the synthetic form`() {
        val html = """<p><a href="https://evil.example/steal">click here</a></p>"""
        val doc = MailHtmlDocument.build(html, emptyMap(), allowRemoteImages = false)
        assertFalse(doc.contains("evil.example"))
        assertTrue(doc.contains("""href="https://link.invalid/0""""))
    }
}
