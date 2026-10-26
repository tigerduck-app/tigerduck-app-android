package org.ntust.app.tigerduck.mail.sanitize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.sanitize.MailReadableHtml.RGBA
import org.ntust.app.tigerduck.ui.screen.mail.MailHtmlDocument
import java.util.Locale

/**
 * Most of these are the Apple app's `MailReadableHTMLTests`, case for case, so the two apps fix
 * the same mail the same way. Its mails pass through `rewriteLinks` before the readability pass;
 * here they come straight from the sanitizer, as [MailHtmlDocument.build] takes them, so their
 * links keep the hrefs they were sent with.
 */
class MailReadableHtmlTest {
    private fun readable(raw: String) = MailReadableHtml(HtmlSanitizer.sanitize(raw, allowRemoteImages = false).html)

    // The Apple app's two pages, so the colors its tests expect are the ones expected here.
    private fun MailReadableHtml.onApp() = adjust(pageBackgroundHex = "#000000", pageForegroundHex = "#ffffff", linkForegroundHex = "#9e9eff")
    private fun MailReadableHtml.onLight() = adjust(pageBackgroundHex = "#ffffff", pageForegroundHex = "#000000", linkForegroundHex = "#0000ee")

    private fun rgb(value: Int) = RGBA(
        red = (value shr 16 and 0xFF) / 255.0,
        green = (value shr 8 and 0xFF) / 255.0,
        blue = (value and 0xFF) / 255.0,
    )

    // --- whether a mail sets colors of its own, which opens it on white paper -------------

    @Test
    fun `a mail says whether it sets colors of its own`() {
        val cases = listOf(
            "<p>Hello</p>" to false,
            """<p style="font-size: 14px; border-left: 1px solid #cccccc">Hello</p>""" to false,
            """<p style="background: #000000">Hello</p>""" to false,
            """<font color="#000000">Hello</font>""" to true,
            """<span style="color: black">Hello</span>""" to true,
            """<p style="background-color: #fafafa">Hello</p>""" to true,
            """<table bgcolor="#ffffff"><tr><td>Hello</td></tr></table>""" to true,
            """<p>Thanks</p><blockquote><p style="color: #1f497d">Quoted</p></blockquote>""" to true,
            """<p>Read <a href="https://a.example" style="color: inherit">more</a></p>""" to false,
            """<span style="color: currentColor; background-color: transparent">Hello</span>""" to false,
            """<p style="background-color: currentColor">Hello</p>""" to true,
            """<p style="color: rgba(0, 0, 0, 0); background-color: #fff0">Hello</p>""" to false,
            """<table bgcolor="transparent"><tr><td style="color: unset">Hello</td></tr></table>""" to false,
            """<p style="color: revert">Hello</p>""" to false,
            """<p style="background-color: revert-layer">Hello</p>""" to false,
            """<p style="color: initial">Hello</p>""" to true,
            """<p style="background-color: initial">Hello</p>""" to false,
            """<p style="color: #333333; color: inherit">Hello</p>""" to false,
            """<p style="color: inherit; color: #333333">Hello</p>""" to true,
            """<font color="#333333" style="color: inherit">Hello</font>""" to false,
            """<table><tr><td bgcolor="#ffffff" style="background-color: transparent">Hello</td></tr></table>""" to false,
            """<p style="color: hsl(0 0% 20%)">Hello</p>""" to true,
        )
        for ((html, setsColors) in cases) {
            assertEquals(html, setsColors, readable(html).setsColors)
        }
    }

    // --- text on a background --------------------------------------------------------------

    @Test
    fun `text on its own background is fixed on either page`() {
        val page = readable("""<p style="background-color: #ffff00; color: #ffffff">Notice</p>""")
        for (html in listOf(page.onApp(), page.onLight())) {
            assertEquals("""<p style="background-color: #ffff00; color: #545454">Notice</p>""", html)
        }
    }

    @Test
    fun `text on an ancestor's background is fixed on either page`() {
        val page = readable(
            """<table bgcolor="#003366" style="color: #ffffff"><tr><td><font color="#000080">Notice</font></td>""" +
                """<td bgcolor="#ffffff" style="color: #000000"><font color="#000080">Notice</font></td></tr></table>""",
        )
        val fixed = """<table bgcolor="#003366" style="color: #ffffff"><tbody><tr><td><font color="#a3c2ff">Notice</font></td>""" +
            """<td bgcolor="#ffffff" style="color: #000000"><font color="#000080">Notice</font></td></tr></tbody></table>"""
        assertEquals(fixed, page.onApp())
        assertEquals(fixed, page.onLight())
    }

    @Test
    fun `text on the page is fixed for the page shown`() {
        val page = readable("""<p><font color="#333333">Dark</font> <font color="#ffffff">Light</font></p>""")
        assertEquals("""<p><font color="#959595">Dark</font> <font color="#ffffff">Light</font></p>""", page.onApp())
        assertEquals("""<p><font color="#333333">Dark</font> <font color="#595959">Light</font></p>""", page.onLight())
    }

    @Test
    fun `a translucent background lets the page show through`() {
        val page = readable("""<div style="background-color: rgba(0, 0, 0, 0.5)"><p style="color: #808080">Notice</p></div>""")
        assertEquals("""<div style="background-color: rgba(0, 0, 0, 0.5)"><p style="color: #959595">Notice</p></div>""", page.onApp())
        assertEquals("""<div style="background-color: rgba(0, 0, 0, 0.5)"><p style="color: #000000">Notice</p></div>""", page.onLight())
    }

    @Test
    fun `translucent layers stack`() {
        val page = readable(
            """<div style="background-color: rgba(255, 0, 0, 0.5)"><div style="background-color: rgba(0, 0, 255, 0.5)">""" +
                """<p style="color: #800080">x</p></div></div>""",
        )
        assertEquals(
            """<div style="background-color: rgba(255, 0, 0, 0.5)"><div style="background-color: rgba(0, 0, 255, 0.5)">""" +
                """<p style="color: #ff91fc">x</p></div></div>""",
            page.onApp(),
        )
        assertEquals(
            """<div style="background-color: rgba(255, 0, 0, 0.5)"><div style="background-color: rgba(0, 0, 255, 0.5); color: #ffffff">""" +
                """<p style="color: #ffffff">x</p></div></div>""",
            page.onLight(),
        )
    }

    @Test
    fun `on a mid tone the better of white and black is the fix`() {
        val page = readable("""<p style="background-color: #777777; color: #808080">Notice</p>""")
        assertEquals("""<p style="background-color: #777777; color: #000000">Notice</p>""", page.onApp())
        assertNull(MailReadableHtml.readable(rgb(0x000000), rgb(0x777777)))
    }

    @Test
    fun `text over a background image keeps its color`() {
        val page = readable(
            """<div style="border-image: linear-gradient(#000000, #000000) fill 1"><font color="#333333">Over an image</font></div>""" +
                """<table background="paper.png"><tr><td style="background-image: url(paper.png)"><font color="#333333">On the page</font></td></tr></table>""",
        )
        assertEquals(
            """<div style="border-image: linear-gradient(#000000, #000000) fill 1"><font color="#333333">Over an image</font></div>""" +
                """<table><tbody><tr><td><font color="#959595">On the page</font></td></tr></tbody></table>""",
            page.onApp(),
        )
    }

    @Test
    fun `an opaque background covers one this cannot read`() {
        val page = readable(
            """<div style="border-image: linear-gradient(#000000, #000000) fill 1"><p style="background-color: #000000">""" +
                """<font color="#333333">Covered</font></p></div>""",
        )
        assertEquals(
            """<div style="border-image: linear-gradient(#000000, #000000) fill 1"><p style="background-color: #000000">""" +
                """<font color="#959595">Covered</font></p></div>""",
            page.onApp(),
        )
    }

    @Test
    fun `the border-image longhand counts as an image`() {
        val page = readable(
            """<div style="border-image-source: linear-gradient(#000000, #000000)"><font color="#333333">a</font></div>""" +
                """<p><font color="#333333">b</font></p>""",
        )
        assertEquals(
            """<div style="border-image-source: linear-gradient(#000000, #000000)"><font color="#333333">a</font></div>""" +
                """<p><font color="#959595">b</font></p>""",
            page.onApp(),
        )
    }

    @Test
    fun `a table with column backgrounds cannot be read`() {
        val page = readable(
            """<table><colgroup><col style="background-color: #000000"></colgroup><tr><td><font color="#333333">a</font></td></tr></table>""" +
                """<table><col style="background-color: #000000"><tr><td><font color="#333333">b</font></td></tr></table>""" +
                """<table><tr><td><font color="#333333">c</font></td></tr></table>""",
        )
        assertEquals(
            """<table><colgroup><col style="background-color: #000000"></colgroup><tbody><tr><td><font color="#333333">a</font></td></tr></tbody></table>""" +
                """<table><colgroup><col style="background-color: #000000"></colgroup><tbody><tr><td><font color="#333333">b</font></td></tr></tbody></table>""" +
                """<table><tbody><tr><td><font color="#959595">c</font></td></tr></tbody></table>""",
            page.onApp(),
        )
    }

    /**
     * WebKit paints a table's background over its grid only, so its caption is judged on what the
     * table sits on, and given back the color the table was given in its place
     * (https://github.com/tigerduck-app/tigerduck-app/issues/677). Chromium paints it the same way.
     */
    @Test
    fun `a caption sits on what its table sits on`() {
        val black = readable("""<table bgcolor="#000000"><caption><font color="#333333">Caption</font></caption><tr><td>Cell</td></tr></table>""")
        assertEquals(
            """<table bgcolor="#000000" style="color: #959595"><caption style="color: #000000"><font color="#333333">Caption</font></caption>""" +
                """<tbody><tr><td>Cell</td></tr></tbody></table>""",
            black.onLight(),
        )
        val white = readable("""<table bgcolor="#ffffff"><caption>Caption</caption><tr><td>Cell</td></tr></table>""")
        assertEquals(
            """<table bgcolor="#ffffff" style="color: #595959"><caption style="color: #ffffff">Caption</caption><tbody><tr><td>Cell</td></tr></tbody></table>""",
            white.onApp(),
        )
        assertEquals("""<table bgcolor="#ffffff"><caption>Caption</caption><tbody><tr><td>Cell</td></tr></tbody></table>""", white.onLight())
    }

    @Test
    fun `a caption out of table layout sits on the table`() {
        val mails = listOf(
            """<table bgcolor="#000000" style="display: block"><caption><font color="#333333">Caption</font></caption><tr><td>Cell</td></tr></table>""",
            """<table bgcolor="#000000"><caption style="display: block"><font color="#333333">Caption</font></caption><tr><td>Cell</td></tr></table>""",
        )
        for (mail in mails) {
            val out = readable(mail).onLight()
            assertFalse(out, out.contains("""<font color="#333333">"""))
            assertFalse(out, out.contains("""style="color: #000000""""))
        }
    }

    @Test
    fun `text on a background color this cannot read keeps its color`() {
        val page = readable(
            """<div style="background-color: hsl(0 0% 0%)"><font color="#333333">a</font></div>""" +
                """<table><tr><td bgcolor="#000000ff"><font color="#333333">b</font></td></tr></table><p><font color="#333333">c</font></p>""",
        )
        assertEquals(
            """<div style="background-color: hsl(0 0% 0%)"><font color="#333333">a</font></div>""" +
                """<table><tbody><tr><td bgcolor="#000000ff"><font color="#333333">b</font></td></tr></tbody></table><p><font color="#959595">c</font></p>""",
            page.onApp(),
        )
    }

    @Test
    fun `an inline background outranks bgcolor`() {
        val page = readable("""<table><tr><td bgcolor="#ffffff" style="background-color: #000000"><font color="#333333">x</font></td></tr></table>""")
        assertEquals(
            """<table><tbody><tr><td bgcolor="#ffffff" style="background-color: #000000"><font color="#959595">x</font></td></tr></tbody></table>""",
            page.onApp(),
        )
    }

    @Test
    fun `an important background outranks a later one`() {
        val page = readable("""<p style="background-color: #000000 !important; background-color: #ffffff; color: #333333">x</p>""")
        assertEquals("""<p style="background-color: #000000 !important; background-color: #ffffff; color: #959595">x</p>""", page.onApp())
    }

    @Test
    fun `a color declaration outranks font color`() {
        val page = readable("""<p><font color="#ffffff" style="color: #333333">a</font><font color="#333333" style="color: hsl(0 0% 20%)">b</font></p>""")
        assertEquals(
            """<p><font color="#ffffff" style="color: #959595">a</font><font color="#333333" style="color: hsl(0 0% 20%)">b</font></p>""",
            page.onApp(),
        )
    }

    @Test
    fun `translucent text is judged as it shows`() {
        val page = readable("""<p style="color: rgba(0, 0, 0, 0.5)">a</p><p style="color: transparent">b</p>""")
        assertEquals("""<p style="color: #959595">a</p><p style="color: transparent">b</p>""", page.onApp())
        assertEquals("""<p style="color: #595959">a</p><p style="color: transparent">b</p>""", page.onLight())
    }

    @Test
    fun `only the text color changes`() {
        val page = readable("""<p style="color: #333333 !important; background-color: transparent; border: 1px solid #333333">Notice</p>""")
        assertEquals(
            """<p style="color: #959595 !important; background-color: transparent; border: 1px solid #333333">Notice</p>""",
            page.onApp(),
        )
    }

    // --- the fix itself ----------------------------------------------------------------------

    @Test
    fun `a fix keeps the hue`() {
        assertEquals(rgb(0x6B98D2), MailReadableHtml.readable(rgb(0x1F497D), rgb(0x000000)))
        assertEquals(rgb(0x5C5C00), MailReadableHtml.readable(rgb(0xFFFF00), rgb(0xFFFFFF)))
    }

    @Test
    fun `every fix reaches the target`() {
        for (background in listOf(0x000000, 0xFFFFFF, 0x777777, 0x3366CC, 0xFFFF00, 0x123456)) {
            val page = rgb(background)
            val best = maxOf(MailReadableHtml.contrast(rgb(0xFFFFFF), page), MailReadableHtml.contrast(rgb(0x000000), page))
            val target = minOf(MailReadableHtml.MINIMUM_CONTRAST, best)
            for (value in 0..0xFFFFFF step 0x3F1F) {
                val text = rgb(value)
                val ratio = MailReadableHtml.contrast(text, page)
                val fixed = MailReadableHtml.readable(text, page)
                val context = "${text.css} on ${page.css}"
                if (fixed != null) {
                    assertTrue(context, ratio < target)
                    assertTrue(context, MailReadableHtml.contrast(fixed, page) >= target)
                } else {
                    assertTrue(context, ratio >= target)
                }
            }
        }
        for (level in 0..255) {
            val gray = rgb(level * 0x010101)
            for (page in listOf(rgb(0x000000), rgb(0xFFFFFF))) {
                val fixed = MailReadableHtml.readable(gray, page) ?: continue
                assertTrue("${gray.css} became ${fixed.css}", fixed.red == fixed.green && fixed.green == fixed.blue)
            }
        }
    }

    // --- text with no color of its own, and links ---------------------------------------------

    @Test
    fun `a highlight with no color of its own is given one`() {
        val html = """<p>Read <span style="background-color: #ffff00">this</span> first</p>"""
        val page = readable(html)
        assertEquals("""<p>Read <span style="background-color: #ffff00; color: #545454">this</span> first</p>""", page.onApp())
        assertEquals(html, page.onLight())
    }

    @Test
    fun `a background gets back the color the sender meant`() {
        val page = readable("""<div style="color: #000000">Hi <span style="background-color: #ffff00">there</span></div>""")
        assertEquals("""<div style="color: #959595">Hi <span style="background-color: #ffff00; color: #000000">there</span></div>""", page.onApp())
    }

    @Test
    fun `every cell on a background of its own gets the fix, not only the first`() {
        val page = readable(
            """<div style="color: #333333"><table><tr><td bgcolor="#1f497d">a</td><td bgcolor="#1f497d">b</td></tr></table></div>""",
        )
        assertEquals(
            """<div style="color: #333333"><table><tbody><tr><td bgcolor="#1f497d" style="color: #e2e2e2">a</td>""" +
                """<td bgcolor="#1f497d" style="color: #e2e2e2">b</td></tr></tbody></table></div>""",
            page.onLight(),
        )
    }

    @Test
    fun `every highlight gets back the color the sender meant, not only the first`() {
        val page = readable(
            """<div style="color: #000000"><span style="background-color: #ffff00">a</span> <span style="background-color: #ffff00">b</span></div>""",
        )
        assertEquals(
            """<div style="color: #959595"><span style="background-color: #ffff00; color: #000000">a</span> """ +
                """<span style="background-color: #ffff00; color: #000000">b</span></div>""",
            page.onApp(),
        )
    }

    @Test
    fun `a color that inherits is the parent's, and fixing goes on below it`() {
        // revert and revert-layer fall back to the browser's own color, which for text that is no
        // link is the parent's.
        for (keyword in listOf("inherit", "currentColor", "unset", "revert", "revert-layer")) {
            val page = readable(
                """<div style="color: #333333"><p style="color: $keyword"><span style="background-color: #ffff00">a</span></p>""" +
                    """<span style="color: $keyword; background-color: #ffff00">b</span></div>""",
            )
            assertEquals(
                keyword,
                """<div style="color: #959595"><p style="color: $keyword"><span style="background-color: #ffff00; color: #333333">a</span></p>""" +
                    """<span style="color: #333333; background-color: #ffff00">b</span></div>""",
                page.onApp(),
            )
        }
    }

    @Test
    fun `a background color set back to its initial value is none`() {
        for (keyword in listOf("initial", "unset")) {
            val page = readable("""<p style="background-color: $keyword; color: #ffffff">x</p>""")
            assertEquals(keyword, """<p style="background-color: $keyword; color: #595959">x</p>""", page.onLight())
        }
    }

    @Test
    fun `a link on the sender's background is fixed`() {
        val page = readable(
            """<table bgcolor="#000000"><tr><td>Text <a href="https://a.example">link</a></td></tr></table>""" +
                """<p><a href="https://b.example">page link</a></p>""",
        )
        assertEquals(
            """<table bgcolor="#000000" style="color: #959595"><tbody><tr><td>Text <a href="https://a.example" style="color: #6092ff">link</a></td></tr></tbody></table>""" +
                """<p><a href="https://b.example">page link</a></p>""",
            page.onLight(),
        )
        assertEquals(
            """<table bgcolor="#000000"><tbody><tr><td>Text <a href="https://a.example">link</a></td></tr></tbody></table>""" +
                """<p><a href="https://b.example">page link</a></p>""",
            page.onApp(),
        )
    }

    @Test
    fun `a link on a dark background of the sender's is given a color that reads there`() {
        val input = """<div style="background-color: #000000"><a href="https://example.com">My Link</a></div>"""
        // #002244 has too little contrast on the sender's #000000.
        val adjusted = MailReadableHtml(input).adjust(
            pageBackgroundHex = "#ffffff",
            pageForegroundHex = "#000000",
            linkForegroundHex = "#002244",
        )
        assertEquals(
            """<div style="background-color: #000000; color: #959595"><a href="https://example.com" style="color: #7399c2">My Link</a></div>""",
            adjusted,
        )
    }

    @Test
    fun `a highlight inside a link starts from the link color`() {
        val page = readable("""<p><a href="https://a.example"><span style="background-color: #ffff00">Marked</span> link</a></p>""")
        assertEquals(
            """<p><a href="https://a.example"><span style="background-color: #ffff00; color: #4e48a1">Marked</span> link</a></p>""",
            page.onApp(),
        )
    }

    @Test
    fun `a link inside colored text keeps the link color`() {
        val page = readable("""<div style="background-color: #000000; color: #ffffff">Text <a href="https://a.example">link</a></div>""")
        assertEquals(
            """<div style="background-color: #000000; color: #ffffff">Text <a href="https://a.example" style="color: #6092ff">link</a></div>""",
            page.onLight(),
        )
    }

    // --- color values -------------------------------------------------------------------------

    @Test
    fun `relative luminance and contrast match WCAG 2 definition`() {
        val white = RGBA(1.0, 1.0, 1.0)
        val black = RGBA(0.0, 0.0, 0.0)
        val ratio = MailReadableHtml.contrast(white, black)
        assertEquals(21.0, ratio, 0.01)

        val same = MailReadableHtml.contrast(white, white)
        assertEquals(1.0, same, 0.001)
    }

    @Test
    fun `every CSS form of a color is read`() {
        val forms = listOf(
            "#333", "#333F", "#333333", "#333333ff", "rgb(51, 51, 51)", "rgba(51, 51, 51, 1)", "RGB(20% 20% 20%)",
            "rgb(51 51 51 / 100%)", "rgba(51 51 51 / 1)",
        )
        for (value in forms) {
            assertEquals(value, rgb(0x333333), MailReadableHtml.parse(value, legacy = false))
        }
    }

    @Test
    fun `named and translucent colors are read`() {
        assertEquals(rgb(0x2F4F4F), MailReadableHtml.parse("DarkSlateGray", legacy = false))
        assertEquals(rgb(0x663399), MailReadableHtml.parse("rebeccapurple", legacy = true))
        assertEquals(0.0, MailReadableHtml.parse("transparent", legacy = false)!!.alpha, 0.0)
        assertEquals(RGBA(1.0, 0.0, 0.0, 0.5), MailReadableHtml.parse("rgba(255, 0, 0, 50%)", legacy = false))
        assertEquals(RGBA(1.0, 0.0, 0.0, 128.0 / 255), MailReadableHtml.parse("#ff000080", legacy = false))
    }

    @Test
    fun `forms this cannot read are not guessed`() {
        val forms = listOf(
            "hsl(0, 0%, 20%)", "currentcolor", "inherit", "var(--ink)", "#33", "#33333", "#ggg", "rgb(51, 51)",
            "rgb(51 51 51 51)", "rgb(20%, 51, 51)", "rgb(nan, 0, 0)", "rgb(0x33, 0, 0)", "rgb (51, 51, 51)",
            // Fullwidth digits, which Character.digit reads as hex and a browser does not.
            "#\uFF13\uFF13\uFF13",
            // Numbers CSS does not take, which toDouble does.
            "rgb(0., 0., 0.)", "rgb(51., 51, 51)", "rgba(51, 51, 51, 1.)", "rgb(50.% 20% 20%)",
        )
        for (value in forms) {
            assertNull(value, MailReadableHtml.parse(value, legacy = false))
        }
    }

    /**
     * `bgcolor` and `<font color>` follow HTML's legacy color rules, which read `#333333ff` or
     * `rgb()` as some other color, so only the forms both rules agree on are read there.
     */
    @Test
    fun `a background CSS does not take is no background`() {
        // Chromium drops rgb(0., 0., 0.), so the dark text is on the white page and reads.
        val html = """<p style="background-color: rgb(0., 0., 0.); color: #333333">Notice</p>"""
        assertEquals(html, readable(html).onLight())
    }

    @Test
    fun `every CSS number form is read`() {
        assertEquals(RGBA(0.5, 0.2, 0.0), MailReadableHtml.parse("rgb(50% 20% -.5%)", legacy = false))
        assertEquals(RGBA(1.0, 0.0, 0.0, 0.5), MailReadableHtml.parse("rgb(+2.55e2 0 0 / 5e-1)", legacy = false))
    }

    @Test
    fun `bgcolor and font color take only the forms HTML reads as CSS does`() {
        assertEquals(rgb(0x333333), MailReadableHtml.parse("#333", legacy = true))
        assertEquals(rgb(0x333333), MailReadableHtml.parse("#333333", legacy = true))
        assertEquals(rgb(0x2F4F4F), MailReadableHtml.parse("DarkSlateGray", legacy = true))
        for (value in listOf("#333f", "#333333ff", "rgb(51, 51, 51)", "333333")) {
            assertNull(value, MailReadableHtml.parse(value, legacy = true))
        }
    }

    @Test
    fun `a fixed color is written as hex`() {
        val page = readable(
            """<p style="color: rgb(51 51 51 / 100%)">a</p><p style="color: hsl(0 0% 20%)">b</p>""" +
                """<p><font color="DarkSlateGray">c</font><font color="#333333ff">d</font></p>""",
        )
        assertEquals(
            """<p style="color: #959595">a</p><p style="color: hsl(0 0% 20%)">b</p><p><font color="#7a9c9c">c</font><font color="#333333ff">d</font></p>""",
            page.onApp(),
        )
    }

    @Test
    fun `font color takes the color the sender wrote, past its surrounding spaces`() {
        val page = MailReadableHtml("""<font color=" #333333 ">x</font>""")
        assertEquals("""<font color=" #959595 ">x</font>""", page.onApp())
    }

    // --- bounds ----------------------------------------------------------------------------------

    @Test
    fun `colors deeper than the cap stay as sent`() {
        val cap = MailReadableHtml.MAXIMUM_DEPTH
        for ((wrappers, fixed) in listOf(cap - 1 to true, cap to false)) {
            val html = "<div>".repeat(wrappers) + """<font color="#333333">x</font>""" + "</div>".repeat(wrappers)
            assertEquals("$wrappers wrappers", fixed, MailReadableHtml(html).onApp().contains("""color="#959595""""))
        }
    }

    @Test
    fun `past the search cap a fix is white or black`() {
        val colors = (0..MailReadableHtml.MAXIMUM_SEARCHES).map {
            String.format(Locale.ROOT, "#%02x%02x%02x", it % 16, it / 16 % 16, it / 256)
        }
        val page = MailReadableHtml(colors.joinToString("") { """<font color="$it">x</font>""" }).onApp()
        assertTrue(page.startsWith("""<font color="#959595">x</font>"""))
        assertTrue(page.endsWith("""<font color="#ffffff">x</font>"""))
        assertEquals(2, page.split("#ffffff").size)
    }

    // --- what a page may change ----------------------------------------------------------------

    @Test
    fun `pages come out the same in any order`() {
        val html = HtmlSanitizer.sanitize(
            """<table bgcolor="#003366"><tr><td><font color="#000080">Notice</font></td><td bgcolor="#ffffff"><font color="#000080">Notice</font></td></tr></table>""",
            allowRemoteImages = false,
        ).html
        val shared = MailReadableHtml(html)
        val pages = listOf(shared.onLight(), shared.onApp(), shared.onLight(), shared.onApp())
        assertEquals(MailReadableHtml(html).onLight(), pages[0])
        assertEquals(MailReadableHtml(html).onApp(), pages[1])
        assertEquals(pages[0], pages[2])
        assertEquals(pages[1], pages[3])
        assertNotEquals(pages[0], pages[1])
    }

    /**
     * The Apple app's parser keeps the bytes of an element it leaves alone; jsoup writes the whole
     * body out again, quoting every attribute alike. The sanitizer's output, which is all this
     * reads, is already quoted so.
     */
    @Test
    fun `an element left alone keeps its color`() {
        assertEquals(
            """<p><font color="#959595">a</font> <font color="#ffffff">b</font></p>""",
            MailReadableHtml("<p><font color=#333333>a</font> <font color='#ffffff'>b</font></p>").onApp(),
        )
    }

    @Test
    fun `link and image targets are listed in order`() {
        val html = """<a href="https://link.invalid/0">x</a><img src="tdcid:a" /><img data-remote-src="https://x.example/p.png" />""" +
            """<p title=" src=&quot;y&quot;">t</p><pre> href="z"</pre>"""
        assertEquals(listOf("href=https://link.invalid/0", "src=tdcid:a"), MailReadableHtml.targets(html))
    }

    @Test
    fun `a page keeps every link and image target it was given`() {
        val input = HtmlSanitizer.sanitize(
            """<p style="color: #333333"><a href="https://example.com/test?a=1&amp;b=2">Link</a><img src="cid:abc"></p>""",
            allowRemoteImages = false,
        ).html
        val adjusted = MailReadableHtml(input).onApp()
        assertNotEquals("the color is fixed", input, adjusted)
        assertEquals(MailReadableHtml.targets(input), MailReadableHtml.targets(adjusted))
        assertEquals(2, MailReadableHtml.targets(adjusted).size)
    }

    @Test
    fun `a target written back in other bytes is the same target`() {
        // jsoup writes this bare & back as &amp;, which the parser reads as the same href.
        val input = """<p style="color: #333333"><a href="https://example.com/test?a=1&b=2">Link</a></p>"""
        assertEquals(
            """<p style="color: #959595"><a href="https://example.com/test?a=1&amp;b=2">Link</a></p>""",
            MailReadableHtml(input).onApp(),
        )
    }

    @Test
    fun `either page of the corpus changes only colors`() {
        val carriers = """<p style="font-family: &quot;A&amp;B&quot;, serif; color: #808080 !important">x</p>""" +
            """<div style="font-family: 'Noto Sans'; background-color: #ffffff">y</div>""" +
            """<div style="background-color: #000000 !important"><a href="https://a.example">z</a></div><font color="#808080">w</font>"""
        val writes = listOf<Pair<(MailReadableHtml) -> String, List<String>>>(
            { page: MailReadableHtml -> page.onApp() } to listOf(
                "color: #959595 !important", """background-color: #ffffff; color: #595959">y""", """<font color="#959595">w""",
            ),
            { page: MailReadableHtml -> page.onLight() } to listOf(
                "color: #595959 !important", """background-color: #000000 !important; color: #959595">""",
                """ style="color: #6092ff">z""", """<font color="#595959">w""",
            ),
        )
        val given = Regex("""; color: #[0-9a-f]{6}(?=")| style="color: #[0-9a-f]{6}"""")
        val hex = Regex("#[0-9a-f]{6}")
        fun colorless(html: String) = hex.replace(given.replace(html, ""), "#")
        for (vector in HtmlSanitizerTest.corpus) {
            val sanitized = HtmlSanitizer.sanitize(vector + carriers, allowRemoteImages = false).html
            val page = MailReadableHtml(sanitized)
            for ((draw, expected) in writes) {
                val html = draw(page)
                for (write in expected) {
                    assertTrue("'$write' missing: $vector -> $html", html.contains(write))
                }
                for (token in HtmlSanitizerTest.forbidden) {
                    assertFalse("'$token' in: $vector", html.lowercase().contains(token))
                }
                assertEquals(vector, MailHtmlDocument.rewriteLinks(sanitized).links, MailHtmlDocument.rewriteLinks(html).links)
                assertEquals(vector, colorless(sanitized), colorless(html))
            }
        }
    }

    // --- one color at a time --------------------------------------------------------------------

    @Test
    fun `readable returns null when text already passes 7 to 1 contrast`() {
        val white = RGBA(1.0, 1.0, 1.0)
        val black = RGBA(0.0, 0.0, 0.0)
        assertNull(MailReadableHtml.readable(white, black))
        assertNull(MailReadableHtml.readable(black, white))
    }

    @Test
    fun `readable shifts dark text on dark background to meet 7 to 1 contrast`() {
        val darkText = RGBA(0.1, 0.1, 0.1)
        val darkBg = RGBA(0.12, 0.12, 0.12)
        val fix = MailReadableHtml.readable(darkText, darkBg)
        assertNotNull(fix)
        val newContrast = MailReadableHtml.contrast(fix!!, darkBg)
        assertTrue("New contrast ($newContrast) should be >= 7.0", newContrast >= 7.0)
    }

    @Test
    fun `readable shifts light text on light background to meet 7 to 1 contrast`() {
        val lightText = RGBA(0.9, 0.9, 0.9)
        val lightBg = RGBA(1.0, 1.0, 1.0)
        val fix = MailReadableHtml.readable(lightText, lightBg)
        assertNotNull(fix)
        val newContrast = MailReadableHtml.contrast(fix!!, lightBg)
        assertTrue("New contrast ($newContrast) should be >= 7.0", newContrast >= 7.0)
    }

    @Test
    fun `dark text on dark page background is adjusted to readable color`() {
        val input = """<p style="color: #222222">Dark text</p>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#9e9eff",
        )
        assertFalse(adjusted.contains("color: #222222"))
        val match = Regex("""color:\s*(#[0-9a-fA-F]{6})""").find(adjusted)
        assertNotNull("Should find adjusted color in output", match)
        val newColor = MailReadableHtml.parse(match!!.groupValues[1], legacy = false)
        assertNotNull(newColor)
        val bg = MailReadableHtml.parse("#121212", legacy = false)!!
        assertTrue(MailReadableHtml.contrast(newColor!!, bg) >= 7.0)
    }

    @Test
    fun `backdrop compositing respects nested colored elements`() {
        val input = """<div style="background-color: #ffffff"><p style="color: #000000">Black on white</p></div>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#9e9eff",
        )
        // Black on white has 21:1 contrast and should not be changed even on a dark page
        assertTrue(adjusted.contains("color: #000000"))
    }

    @Test
    fun `font tag color attribute is adjusted`() {
        val input = """<font color="#111111">Old style text</font>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#9e9eff",
        )
        assertFalse(adjusted.contains("""color="#111111""""))
        assertTrue(adjusted.contains("""color="#"""))
    }
}
