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
}
