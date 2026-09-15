package org.ntust.app.tigerduck.mail.sanitize

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.ntust.app.tigerduck.mail.mime.TextCleaning

data class MailLink(val text: String, val href: String)

data class SanitizedHtml(
    val html: String,
    val blockedRemoteImages: Int,
    val remoteImageUrls: Set<String>,
    val links: List<MailLink>,
)

/**
 * Spec appendix A.2. This is the second line of defence: the first is the
 * web view (JavaScript off, network blocked). jsoup must stay at 1.23.1 or
 * later (GHSA-pmhh-3w7g-xqp8).
 */
object HtmlSanitizer {
    const val REMOTE_SRC_ATTR = "data-remote-src"

    private const val REMOVE_WITH_CONTENT =
        "script, style, head, title, iframe, frame, frameset, object, embed, applet, form, input, " +
            "button, select, option, textarea, meta, link, base, svg, math, audio, video, source, " +
            "track, canvas, noscript, template"

    private val TAGS = arrayOf(
        "a", "abbr", "b", "big", "blockquote", "br", "caption", "center", "cite", "code", "col",
        "colgroup", "dd", "del", "div", "dl", "dt", "em", "font", "h1", "h2", "h3", "h4", "h5", "h6",
        "hr", "i", "img", "ins", "kbd", "li", "ol", "p", "pre", "q", "s", "small", "span", "strike",
        "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "tt", "u", "ul",
    )
    private val DATA_IMAGE = Regex("^data:image/(png|jpeg|gif|webp)[;,]", RegexOption.IGNORE_CASE)
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "dd", "dt", "hr",
    )

    fun sanitize(html: String, allowRemoteImages: Boolean): SanitizedHtml {
        val dirty = Jsoup.parse(html)
        dirty.select(REMOVE_WITH_CONTENT).remove()

        val remote = linkedSetOf<String>()
        var blocked = 0
        for (img in dirty.select("img")) {
            img.removeAttr(REMOTE_SRC_ATTR)
            val src = img.attr("src").trim()
            img.attr("src", src)
            val lower = src.lowercase()
            when {
                lower.startsWith("http://") || lower.startsWith("https://") -> {
                    remote += src
                    if (!allowRemoteImages) {
                        img.removeAttr("src")
                        img.attr(REMOTE_SRC_ATTR, src)
                        blocked++
                    }
                }
                lower.startsWith("cid:") -> Unit
                DATA_IMAGE.containsMatchIn(src) -> Unit
                else -> img.removeAttr("src")
            }
        }

        val clean = Cleaner(safelist(allowRemoteImages)).clean(dirty)
        for (element in clean.select("[style]")) {
            val filtered = CssFilter.filter(element.attr("style"))
            if (filtered == null) element.removeAttr("style") else element.attr("style", filtered)
        }
        val links = clean.select("a[href]").map { MailLink(TextCleaning.clean(it.text()), it.attr("href")) }
        clean.outputSettings().prettyPrint(false)
        return SanitizedHtml(clean.body().html(), blocked, remote, links)
    }

    /** Text with line breaks at block boundaries, for the plain-text view when a mail has no text part. */
    fun plainText(sanitizedHtml: String): String {
        val body = Jsoup.parseBodyFragment(sanitizedHtml).body()
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) sb.append(node.text())
                else if (node is Element && node.normalName() in BLOCK_TAGS && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCK_TAGS && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }
        }, body)
        return sb.toString().lines().joinToString("\n") { it.trim() }.trim('\n', ' ')
    }

    private fun safelist(allowRemoteImages: Boolean): Safelist = Safelist().apply {
        addTags(*TAGS)
        addAttributes(":all", "style", "dir", "lang", "title", "align")
        addAttributes("a", "href")
        addAttributes("img", "src", "alt", "width", "height", "border", REMOTE_SRC_ATTR)
        addAttributes("table", "width", "height", "border", "cellpadding", "cellspacing", "bgcolor")
        addAttributes("td", "width", "height", "bgcolor", "valign", "colspan", "rowspan", "nowrap")
        addAttributes("th", "width", "height", "bgcolor", "valign", "colspan", "rowspan", "nowrap")
        addAttributes("tr", "bgcolor", "valign")
        addAttributes("col", "span", "width")
        addAttributes("colgroup", "span", "width")
        addAttributes("font", "color", "face", "size")
        addAttributes("ol", "start", "type")
        addAttributes("ul", "type")
        addAttributes("li", "value")
        addProtocols("a", "href", "http", "https", "mailto")
        if (allowRemoteImages) addProtocols("img", "src", "cid", "data", "http", "https")
        else addProtocols("img", "src", "cid", "data")
    }
}
