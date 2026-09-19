package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.jsoup.Jsoup
import org.junit.Test
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.InlineImage
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.sanitize.MailLink

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
        val doc = MailHtmlDocument.build("<p>x</p>", emptyMap(), allowRemoteImages = true).html
        assertTrue(doc.contains("img-src data: https: http:"))
        assertTrue(doc.contains("<body><p>x</p></body>"))
    }

    // --- rewriteLinks: every <a href> becomes its index into the links it returns -------------

    @Test
    fun `rewriteLinks replaces every href with its document-order index and keeps none of the originals`() {
        val html = """<p><a href="https://a.example">A</a> <a href="https://b.example/x?y=1">B</a></p>"""
        val out = MailHtmlDocument.rewriteLinks(html)
        assertTrue(out.html.contains("""href="https://link.invalid/0""""))
        assertTrue(out.html.contains("""href="https://link.invalid/1""""))
        assertFalse(out.html.contains("a.example"))
        assertFalse(out.html.contains("b.example"))
        assertEquals(listOf(MailLink("A", "https://a.example"), MailLink("B", "https://b.example/x?y=1")), out.links)
    }

    @Test
    fun `rewriteLinks reindexes duplicate hrefs to their own separate indices`() {
        val html = """<p><a href="https://ntust.edu.tw">first</a> <a href="https://ntust.edu.tw">second</a></p>"""
        val out = MailHtmlDocument.rewriteLinks(html)
        assertTrue(out.html.contains("""href="https://link.invalid/0""""))
        assertTrue(out.html.contains("""href="https://link.invalid/1""""))
        assertFalse(out.html.contains("ntust.edu.tw"))
        assertEquals(listOf("first", "second"), out.links.map { it.text })
    }

    @Test
    fun `rewriteLinks leaves html with no links unchanged`() {
        assertEquals(LinkedHtml("<p>no links here</p>", emptyList()), MailHtmlDocument.rewriteLinks("<p>no links here</p>"))
    }

    @Test
    fun `build's document never contains an original href, only the synthetic form`() {
        val html = """<p><a href="https://evil.example/steal">click here</a></p>"""
        val doc = MailHtmlDocument.build(html, emptyMap(), allowRemoteImages = false)
        assertFalse(doc.html.contains("evil.example"))
        assertTrue(doc.html.contains("""href="https://link.invalid/0""""))
        assertEquals(listOf(MailLink("click here", "https://evil.example/steal")), doc.links)
    }

    // --- the link list and the anchors the WebView parses stay in lockstep -------------------

    // Trees the Cleaner builds but the HTML parser never would (a dropped element's children are
    // kept): parsing the sanitized markup again clones the <a> around "ntust.edu.tw".
    private val clonedAnchorMails = listOf(
        """<p><a href="https://a.example">A<marquee><p>ntust.edu.tw</p></marquee>C</a></p><a href="https://evil.example"></a>""",
        """<ul><li><a href="https://a.example">A<section><li>ntust.edu.tw</li></section>C</a></li></ul><a href="https://evil.example">e</a>""",
        """<ul><li><a href="https://a.example">A<details><li>ntust.edu.tw</li></details></a></li></ul><a href="https://evil.example">e</a>""",
        """<dl><dt><a href="https://a.example">A<marquee><dd>ntust.edu.tw</dd></marquee>C</a></dt></dl><a href="https://evil.example">e</a>""",
        """<p><a href="https://a.example">A<marquee><pre>ntust.edu.tw</pre></marquee>C</a></p><a href="https://evil.example">e</a>""",
        """<p><a href="https://a.example">A<marquee><div>ntust.edu.tw</div></marquee>C</a></p><a href="https://evil.example">e</a>""",
        """<p><a href="https://a.example">A<marquee><table><tr><td>ntust.edu.tw</td></tr></table></marquee>C</a></p><a href="https://evil.example">e</a>""",
    )

    /**
     * Parses [document] the way the WebView does and checks each anchor `n` against `links[n]`: the
     * synthetic href for `n`, its own text, and the href the anchor had in the tree the rewrite walked.
     * Links that were stripped instead must leave no tappable anchor at all.
     */
    private fun assertLockstep(context: String, walkedHtml: String, document: LinkedHtml) {
        val walked = Jsoup.parseBodyFragment(walkedHtml).select("a[href]")
        val loaded = Jsoup.parse(document.html).select("a[href]")
        if (document.links.isEmpty()) {
            assertTrue(context, loaded.isEmpty())
            return
        }
        assertEquals(context, walked.size, document.links.size)
        assertEquals(context, document.links.size, loaded.size)
        loaded.forEachIndexed { n, anchor ->
            assertEquals(context, "https://link.invalid/$n", anchor.attr("href"))
            assertEquals(context, TextCleaning.clean(anchor.text()), document.links[n].text)
            assertEquals(context, walked[n].attr("href"), document.links[n].href)
        }
    }

    @Test
    fun `every anchor the WebView parses maps to the list entry with its own text and href`() {
        for (mail in clonedAnchorMails) {
            val sanitized = HtmlSanitizer.sanitize(mail, allowRemoteImages = false)
            val document = MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false)
            // None of these needs the fail-closed path: the rewrite's own tree is stable.
            assertTrue(mail, document.links.isNotEmpty())
            assertLockstep(mail, sanitized.html, document)
        }
    }

    @Test
    fun `the tapped clone of a link carries that link's href and its own text`() {
        val sanitized = HtmlSanitizer.sanitize(clonedAnchorMails.first(), allowRemoteImages = false)
        val document = MailHtmlDocument.rewriteLinks(sanitized.html)
        assertEquals(
            listOf(
                MailLink("A", "https://a.example"),
                MailLink("ntust.edu.tw", "https://a.example"),
                MailLink("C", "https://a.example"),
                MailLink("", "https://evil.example"),
            ),
            document.links,
        )
    }

    @Test
    fun `anchors that do not survive the WebView's re-parse are stripped of every href`() {
        // Each parse drops the newline right after <pre>, so the anchor's text read off the tree
        // this walks ("y" and two newlines, "x") is not the text the WebView ends up with.
        val newline = Char(10).toString()
        val mail = """<a href="https://a.example">y<pre>""" + newline.repeat(4) +
            """x</pre></a><a href="https://b.example">b</a>"""
        val sanitized = HtmlSanitizer.sanitize(mail, allowRemoteImages = false)
        val document = MailHtmlDocument.rewriteLinks(sanitized.html)
        assertTrue(document.links.isEmpty())
        assertFalse(document.html.contains("href"))
        assertTrue(Jsoup.parseBodyFragment(document.html).select("a").isNotEmpty())
        assertLockstep(mail, sanitized.html, MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false))
    }

    @Test
    fun `links are rewritten after cid inlining, so the check covers the body the WebView gets`() {
        // inlineCids is a text substitution: a cid-looking text runs to the next quote and swallows
        // markup. Done after the rewrite, it would merge two checked anchors into one.
        val mail = """<a href="https://a.example">src="cid:x</a> <a href="https://evil.example">ntust.edu.tw</a>"""
        val sanitized = HtmlSanitizer.sanitize(mail, allowRemoteImages = false)
        val document = MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false)
        assertLockstep(mail, MailHtmlDocument.inlineCids(sanitized.html, emptyMap()), document)
    }
}
