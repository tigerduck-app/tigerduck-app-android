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
import org.ntust.app.tigerduck.mail.sanitize.MailReadableHtml

class MailHtmlDocumentTest {
    private val png = InlineImage("image/png", byteArrayOf(1, 2, 3))
    private val dark = MailHtmlTheme(background = "#121212", foreground = "#e6e6e6", isDark = true)
    private val light = MailHtmlTheme(background = "#ffffff", foreground = "#1b1b1b", isDark = false)

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
        val doc = MailHtmlDocument.build("<p>x</p>", emptyMap(), allowRemoteImages = true, theme = dark).html
        assertTrue(doc.contains("img-src data: https: http:"))
        assertTrue(doc.contains("<body><p>x</p></body>"))
    }

    // --- theme: the page's own surface, and a sender's colors where they read ---------------

    @Test
    fun `dark theme colors reach the document`() {
        val html = MailHtmlDocument.build("<p>hi</p>", emptyMap(), false, dark).html
        assertTrue(html, html.contains("background:#121212"))
        assertTrue(html, html.contains("color:#e6e6e6"))
    }

    @Test
    fun `color-scheme follows the theme`() {
        assertTrue(MailHtmlDocument.build("<p>hi</p>", emptyMap(), false, dark).html.contains("color-scheme:dark"))
        assertTrue(MailHtmlDocument.build("<p>hi</p>", emptyMap(), false, light).html.contains("color-scheme:light"))
    }

    // The sanitizer keeps background-color and drops the background shorthand, so these use the
    // one a sanitized mail can carry.
    @Test
    fun `a sender's color that reads on its own background is left as sent`() {
        val body = """<p style="background-color: #ffeeaa; color: #003300">branded</p>"""
        val html = MailHtmlDocument.build(body, emptyMap(), false, dark).html
        assertTrue(html, html.contains("<body>$body</body>"))
    }

    @Test
    fun `a sender's color that does not read on the page is moved to 7 to 1`() {
        val body = """<p style="color: #003300">branded</p>"""
        val html = MailHtmlDocument.build(body, emptyMap(), false, dark).html
        assertTrue(html, html.contains("""<body><p style="color: #79ab75">branded</p></body>"""))
        val (moved, page) = listOf("#79ab75", "#121212").map { MailReadableHtml.parse(it, legacy = false)!! }
        assertTrue(MailReadableHtml.contrast(moved, page) >= MailReadableHtml.MINIMUM_CONTRAST)
    }

    @Test
    fun `white is no longer hardcoded`() {
        val html = MailHtmlDocument.build("<p>hi</p>", emptyMap(), false, dark).html
        assertFalse(html, html.contains("background:#fff;"))
    }

    @Test
    fun `view in light mode is white paper with black text in the light scheme`() {
        val html = MailHtmlDocument.build("<p>hi</p>", emptyMap(), false, MailHtmlTheme.LIGHT).html
        assertTrue(html, html.contains("color-scheme:light"))
        assertTrue(html, html.contains("background:#ffffff;color:#000000"))
    }

    // --- links: drawn in the color their contrast is judged in ------------------------------

    @Test
    fun `the page draws links in its theme's link color`() {
        val html = MailHtmlDocument.build("<p>hi</p>", emptyMap(), false, dark.copy(link = "#123456")).html
        assertTrue(html, html.contains("a[href]{color:#123456}"))
    }

    @Test
    fun `a page's links default to the colors Chromium draws them in, where those read`() {
        assertEquals("#9e9eff", dark.link)
        assertEquals("#0000ee", light.link)
        assertEquals("#0000ee", MailHtmlTheme.LIGHT.link)
        assertEquals("#0000ee", MailHtmlTheme(background = "#f2f2f7", foreground = "#1c1c1e", isDark = false).link)
    }

    @Test
    fun `on the app's dark surface a link is drawn in a color that reads at 7 to 1 there`() {
        // #9e9eff is 4.75:1 on the default dark theme's #3a3a3c.
        val surface = MailHtmlTheme(background = "#3a3a3c", foreground = "#ffffff", isDark = true)
        val (link, page) = listOf(surface.link, "#3a3a3c").map { MailReadableHtml.parse(it, legacy = false)!! }
        assertTrue(surface.link, MailReadableHtml.contrast(link, page) >= MailReadableHtml.MINIMUM_CONTRAST)
        val html = MailHtmlDocument.build("""<p><a href="https://example.com">x</a></p>""", emptyMap(), false, surface).html
        assertTrue(html, html.contains("a[href]{color:${surface.link}}"))
        assertTrue(html, html.contains("""<a href="https://link.invalid/0">x</a>"""))
    }

    @Test
    fun `a link on the sender's white background is judged in the color the paper draws it in`() {
        // #0000ee reads on white at 9.4:1, so both links stay the paper's own blue.
        val body = """<table bgcolor="#ffffff"><tbody><tr><td><a href="https://example.com/a">Sign up</a></td></tr></tbody></table>""" +
            """<p><a href="https://example.com/b">Another</a></p>"""
        val html = MailHtmlDocument.build(body, emptyMap(), false, MailHtmlTheme.LIGHT).html
        assertTrue(html, html.contains("""<td><a href="https://link.invalid/0">Sign up</a></td>"""))
    }

    @Test
    fun `a link on a sender's background it does not read on is given a color that does`() {
        val body = """<table bgcolor="#333333"><tbody><tr><td><a href="https://example.com/a">Sign up</a></td></tr></tbody></table>"""
        val html = MailHtmlDocument.build(body, emptyMap(), false, dark).html
        val given = Regex("""<a href="https://link.invalid/0" style="color: (#[0-9a-f]{6})">""").find(html)?.groupValues?.get(1)
        assertTrue(html, given != null)
        val (link, cell) = listOf(given!!, "#333333").map { MailReadableHtml.parse(it, legacy = false)!! }
        assertTrue(MailReadableHtml.contrast(link, cell) >= MailReadableHtml.MINIMUM_CONTRAST)
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
        val doc = MailHtmlDocument.build(html, emptyMap(), allowRemoteImages = false, theme = dark)
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
            val document = MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false, theme = dark)
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
        assertLockstep(mail, sanitized.html, MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false, theme = dark))
    }

    @Test
    fun `links stripped of their hrefs are judged again as the text they become`() {
        // As above, each parse drops a newline after <pre>, so the anchors fall out of lockstep.
        // Judged as a link, the anchor's blue reads on its own white; stripped, it inherits the
        // div's white, on that same white.
        val newline = Char(10).toString()
        val mail = """<div style="background-color: #000000; color: #ffffff"><a href="https://a.example" style="background-color: #ffffff">y<pre>""" +
            newline.repeat(8) + """x</pre></a></div>"""
        val sanitized = HtmlSanitizer.sanitize(mail, allowRemoteImages = false)
        val document = MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false, theme = MailHtmlTheme.LIGHT)
        assertTrue(document.links.isEmpty())
        val page = Jsoup.parse(document.html)
        assertTrue(document.html, page.select("[href]").isEmpty())
        val anchor = page.selectFirst("a")!!
        assertEquals("background-color: #ffffff; color: #595959", anchor.attr("style"))
    }

    @Test
    fun `text that reads like a target does not stop stripped links being judged again`() {
        // The newline the second pass's parse drops sits before text that reads like an href.
        val newline = Char(10).toString()
        val mail = """<div style="background-color: #000000; color: #ffffff"><a href="https://a.example" style="background-color: #ffffff">y<pre>""" +
            newline.repeat(3) + """href="z"</pre></a></div>"""
        val sanitized = HtmlSanitizer.sanitize(mail, allowRemoteImages = false)
        val document = MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false, theme = MailHtmlTheme.LIGHT)
        assertTrue(document.links.isEmpty())
        val anchor = Jsoup.parse(document.html).selectFirst("a")!!
        assertEquals("background-color: #ffffff; color: #595959", anchor.attr("style"))
    }

    @Test
    fun `links are rewritten after cid inlining, so the check covers the body the WebView gets`() {
        // inlineCids is a text substitution: a cid-looking text runs to the next quote and swallows
        // markup. Done after the rewrite, it would merge two checked anchors into one.
        val mail = """<a href="https://a.example">src="cid:x</a> <a href="https://evil.example">ntust.edu.tw</a>"""
        val sanitized = HtmlSanitizer.sanitize(mail, allowRemoteImages = false)
        val document = MailHtmlDocument.build(sanitized.html, emptyMap(), allowRemoteImages = false, theme = dark)
        assertLockstep(mail, MailHtmlDocument.inlineCids(sanitized.html, emptyMap()), document)
    }
}
