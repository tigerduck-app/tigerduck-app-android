package org.ntust.app.tigerduck.network.moodle

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/**
 * Moodle's HTML, such as an announcement's message, as the app shows it: Markdown for the body,
 * which the Markdown renderer draws, and one line of plain text for a preview. Ported from iOS
 * `MoodleHTMLText`.
 */
object MoodleHtmlText {

    /** The text of [html] with every run of whitespace, line breaks included, as one space. */
    fun plainText(html: String): String {
        if (html.isEmpty()) return ""
        val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull()
            ?: return collapsed(html)
        val text = StringBuilder()
        appendPlainText(body, text)
        return collapsed(text.toString())
    }

    /**
     * Markdown for [html]: paragraphs, line breaks, headings, lists, quotes, emphasis and links,
     * with the text escaped so it never reads as Markdown. An image becomes a link to it. Links
     * resolve against [baseUrl], and only http, https and mailto ones stay links.
     */
    fun markdown(html: String, baseUrl: String = MoodleWebservice.SITE): String {
        if (html.isEmpty()) return ""
        val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull()
            ?: return escaped(collapsed(html))
        return MarkdownWriter(baseUrl).blocks(body).joinToString("\n\n")
    }

    /**
     * [html] with each file Moodle keeps in the text's own area, written `@@PLUGINFILE@@/<path>`,
     * pointing at that file in [files]. An image whose file is not there is left out, and a link
     * keeps only its text.
     */
    fun resolvingPluginFiles(html: String, files: List<MoodleFile>): String {
        if (!html.contains(PLUGIN_FILE_PLACEHOLDER)) return html
        val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return html
        document.outputSettings().prettyPrint(false)
        val urls = HashMap<String, String>()
        for (file in files) urls.putIfAbsent((file.path ?: "/") + file.name, file.url)
        for (element in document.body().allElements.toList()) {
            for (attribute in listOf("src", "href")) {
                val value = element.attr(attribute)
                if (!value.startsWith(PLUGIN_FILE_PLACEHOLDER)) continue
                val path = value.drop(PLUGIN_FILE_PLACEHOLDER.length).takeWhile { it != '?' && it != '#' }
                val decoded = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
                val url = urls[decoded] ?: urls[path]
                when {
                    url != null -> element.attr(attribute, url)
                    element.tagName().equals("img", ignoreCase = true) -> element.remove()
                    else -> element.unwrap()
                }
            }
        }
        return document.body().html()
    }

    private const val PLUGIN_FILE_PLACEHOLDER = "@@PLUGINFILE@@"

    private fun appendPlainText(node: Node, text: StringBuilder) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> text.append(child.wholeText)
                is Element -> {
                    val tag = child.tagName().lowercase()
                    if (tag in SKIPPED_TAGS) continue
                    // A block or a break ends a word even where the HTML has no space around it.
                    val separates = tag == "br" || MarkdownWriter.separatesText(tag)
                    if (separates) text.append(' ')
                    appendPlainText(child, text)
                    if (separates) text.append(' ')
                }
            }
        }
    }

    private fun collapsed(text: String): String =
        text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private val WHITESPACE = Regex("[\\s\\u00A0\\u2028\\u2029\\u3000]+")

    internal val SKIPPED_TAGS = setOf("script", "style", "head", "title", "noscript", "template")

    internal fun collapsedKeepingEdges(text: String): String {
        val result = StringBuilder()
        var lastWasSpace = false
        for (c in text) {
            if (c.isWhitespace() && c != ' ') {
                if (!lastWasSpace) result.append(' ')
                lastWasSpace = true
            } else {
                result.append(c)
                lastWasSpace = false
            }
        }
        return result.toString()
    }

    /** Backslash-escapes the ASCII punctuation Markdown would read as syntax anywhere in a line. */
    internal fun escaped(text: String): String {
        val result = StringBuilder()
        for (c in text) {
            if (c in "\\`*_[]<>#|~&") result.append('\\')
            result.append(c)
        }
        return result.toString()
    }

    /** Escapes what reads as a list item, a heading underline or a thematic break at a line's start. */
    internal fun escapedLineStart(text: String): String {
        val first = text.firstOrNull() ?: return text
        if (first in "-+=") return "\\" + text
        val digits = text.takeWhile { it in '0'..'9' }
        if (digits.isNotEmpty()) {
            val next = text.getOrNull(digits.length)
            if (next == '.' || next == ')') return digits + "\\" + text.drop(digits.length)
        }
        return text
    }
}

private class MarkdownWriter(private val baseUrl: String) {

    /** The Markdown blocks of [element]'s children; inline runs between blocks become paragraphs. */
    fun blocks(element: Element): List<String> {
        val result = mutableListOf<String>()
        val paragraph = StringBuilder()
        fun endParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) result += text
            paragraph.setLength(0)
        }
        for (child in element.childNodes()) {
            if (child is TextNode) {
                appendText(child.wholeText, paragraph)
                continue
            }
            if (child !is Element) continue
            val tag = child.tagName().lowercase()
            if (tag in MoodleHtmlText.SKIPPED_TAGS) continue
            val level = headingLevel(tag)
            when {
                tag in BLOCK_TAGS -> {
                    endParagraph()
                    result += blocks(child)
                }
                level != null -> {
                    endParagraph()
                    val text = inline(child).trim()
                    if (text.isNotEmpty()) result += "#".repeat(level) + " " + text
                }
                tag == "ul" || tag == "ol" -> {
                    endParagraph()
                    val list = list(child, ordered = tag == "ol")
                    if (list.isNotEmpty()) result += list
                }
                tag == "blockquote" -> {
                    endParagraph()
                    val quoted = blocks(child).joinToString("\n\n")
                    if (quoted.isNotEmpty()) {
                        result += quoted.split("\n").joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
                    }
                }
                tag == "pre" -> {
                    endParagraph()
                    val code = child.wholeText()
                    if (code.isNotBlank()) {
                        // Longer than any run of backticks inside, so a fence in the code cannot end it.
                        val longestRun = Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0
                        val fence = "`".repeat(maxOf(3, longestRun + 1))
                        result += fence + "\n" + code.trim('\n', '\r') + "\n" + fence
                    }
                }
                tag == "hr" -> {
                    endParagraph()
                    result += "---"
                }
                tag == "br" -> paragraph.append("  \n")
                else -> paragraph.append(inline(child))
            }
        }
        endParagraph()
        return result
    }

    /** Text in a paragraph: collapsed, escaped, and escaped again where it opens a line. */
    private fun appendText(raw: String, paragraph: StringBuilder) {
        var text = MoodleHtmlText.collapsedKeepingEdges(raw)
        val opensLine = paragraph.isEmpty() || paragraph.endsWith("\n")
        if (opensLine) {
            text = text.trimStart(' ')
            if (text.isEmpty()) return
            paragraph.append(MoodleHtmlText.escapedLineStart(MoodleHtmlText.escaped(text)))
        } else {
            paragraph.append(MoodleHtmlText.escaped(text))
        }
    }

    private fun inline(element: Element): String {
        val tag = element.tagName().lowercase()
        when (tag) {
            "br" -> return "  \n"
            "img" -> return image(element)
            "code", "kbd", "samp", "tt" -> {
                val code = MoodleHtmlText.collapsedKeepingEdges(element.text())
                return if (code.isBlank() || code.contains('`')) MoodleHtmlText.escaped(code) else "`$code`"
            }
        }
        val content = StringBuilder()
        for (child in element.childNodes()) {
            if (child is TextNode) {
                content.append(MoodleHtmlText.escaped(MoodleHtmlText.collapsedKeepingEdges(child.wholeText)))
            } else if (child is Element && child.tagName().lowercase() !in MoodleHtmlText.SKIPPED_TAGS) {
                content.append(inline(child))
            }
        }
        return when (tag) {
            "strong", "b" -> wrapped(content.toString(), "**")
            "em", "i", "cite" -> wrapped(content.toString(), "*")
            "a" -> link(content.toString(), element.attr("href"))
            else -> content.toString()
        }
    }

    /** `**text**` with the spaces at its edges moved outside, which emphasis needs to open and close. */
    private fun wrapped(content: String, marker: String): String {
        val text = content.trim(' ')
        if (text.isEmpty()) return content
        val leading = if (content.startsWith(" ")) " " else ""
        val trailing = if (content.endsWith(" ")) " " else ""
        return leading + marker + text + marker + trailing
    }

    private fun link(content: String, href: String): String {
        val url = linkUrl(href, baseUrl) ?: return content
        val text = content.trim(' ')
        val label = text.ifEmpty { MoodleHtmlText.escaped(url) }
        return "[$label](${destination(url)})"
    }

    private fun image(element: Element): String {
        val url = linkUrl(element.attr("src"), baseUrl) ?: return ""
        if (url.startsWith("mailto:", ignoreCase = true)) return ""
        val alt = MoodleHtmlText.collapsedKeepingEdges(element.attr("alt")).trim(' ')
        val name = alt.ifEmpty {
            val last = url.substringBefore('?').substringAfterLast('/')
            runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        }
        return "[${MoodleHtmlText.escaped(name.ifEmpty { url })}](${destination(url)})"
    }

    /**
     * [url] as a bare link destination, with what would end or break one percent-encoded. The
     * angle-bracket form, which needs none of this, is not one the app's Markdown renderer reads.
     */
    private fun destination(url: String): String = buildString {
        for (c in url) {
            when (c) {
                ' ' -> append("%20")
                '(' -> append("%28")
                ')' -> append("%29")
                '<' -> append("%3C")
                '>' -> append("%3E")
                else -> append(c)
            }
        }
    }

    private fun list(element: Element, ordered: Boolean): String {
        val lines = mutableListOf<String>()
        // CommonMark numbers a list with at most nine digits.
        var number = (element.attr("start").toIntOrNull() ?: 1).coerceIn(0, 999_999_000)
        for (item in element.children()) {
            if (item.tagName().lowercase() != "li") continue
            val marker = if (ordered) "$number. " else "- "
            val content = blocks(item).joinToString("\n")
            val pad = " ".repeat(marker.length)
            content.split("\n").forEachIndexed { index, line ->
                lines += if (index == 0) marker + line else if (line.isEmpty()) "" else pad + line
            }
            if (content.isEmpty()) lines[lines.lastIndex] = marker.trim()
            number++
        }
        return lines.joinToString("\n")
    }

    companion object {
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "main", "aside", "header", "footer", "nav", "center",
            "address", "figure", "figcaption", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "dl", "dt", "dd", "form", "fieldset", "details", "summary",
        )

        fun separatesText(tag: String): Boolean =
            tag in BLOCK_TAGS || headingLevel(tag) != null ||
                tag in setOf("ul", "ol", "li", "blockquote", "pre", "hr")

        fun headingLevel(tag: String): Int? {
            if (tag.length != 2 || tag[0] != 'h') return null
            return tag[1].digitToIntOrNull()?.takeIf { it in 1..6 }
        }

        /** The link [href] names, made absolute against [baseUrl], when it is http, https or mailto. */
        fun linkUrl(href: String, baseUrl: String): String? {
            val trimmed = href.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("mailto:", ignoreCase = true)) return trimmed
            val resolved = runCatching { URI(baseUrl).resolve(trimmed.replace(" ", "%20")) }.getOrNull()
                ?: runCatching { java.net.URL(java.net.URL(baseUrl), trimmed).toURI() }.getOrNull()
                ?: return null
            val scheme = resolved.scheme?.lowercase() ?: return null
            if (scheme !in setOf("http", "https", "mailto")) return null
            return resolved.toString()
        }
    }
}
