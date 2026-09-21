package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.InlineImage
import org.ntust.app.tigerduck.mail.sanitize.MailLink
import java.net.URLDecoder
import java.util.Base64

/**
 * HTML for the WebView in which every `<a href>` is `https://link.invalid/<n>`, [links]`[n]` being the
 * text and href that anchor had. [links] is empty and no `href` is left at all when the anchors could not
 * be kept in lockstep with it (see [MailHtmlDocument.rewriteLinks]).
 */
data class LinkedHtml(val html: String, val links: List<MailLink>)

/**
 * The page's own surface, so a mail reads as part of the app rather than as white paper in a
 * dark room. Only the page is themed: a sender's own colours are never rewritten, because the
 * alternative distorts logos, screenshots and branded mail with no way for the reader to tell.
 * [isDark] drives `color-scheme`, which is what makes mail that opts into `prefers-color-scheme`
 * follow along, and makes UA-default form controls legible.
 */
data class MailHtmlTheme(val background: String, val foreground: String, val isDark: Boolean)

/** Wraps sanitized mail HTML for the locked-down WebView (spec §9.3): CSP, paper styling, inlined cid images. */
object MailHtmlDocument {
    private val INLINE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    private val CID_SRC = Regex("""src\s*=\s*"cid:([^"]*)"""", RegexOption.IGNORE_CASE)

    fun csp(allowRemoteImages: Boolean): String =
        "default-src 'none'; img-src data:${if (allowRemoteImages) " https: http:" else ""}; style-src 'unsafe-inline'"

    /** `cid:` sources become data: URIs from parts already fetched; unknown or non-image parts are dropped. */
    fun inlineCids(html: String, images: Map<String, InlineImage>): String {
        val byId = images.mapKeys { normalize(it.key) }
        return CID_SRC.replace(html) { match ->
            val raw = match.groupValues[1]
            val image = byId[normalize(raw)] ?: byId[normalize(decode(raw))]
            val type = image?.contentType?.lowercase()
            if (image == null || type !in INLINE_TYPES) {
                "src=\"\""
            } else {
                "src=\"data:$type;base64,${Base64.getEncoder().encodeToString(image.bytes)}\""
            }
        }
    }

    /**
     * Replaces every `<a href>` with `https://link.invalid/<n>` and returns, as [LinkedHtml.links], the
     * text and original href of anchor `n` -- both read off the very tree this walks and rewrites, never
     * off `SanitizedHtml.links`. Those two lists can disagree: the sanitizer's Cleaner drops some elements
     * but keeps their children (`marquee`, `section`, `details`, ...), building trees the HTML parser never
     * would, so parsing its output again can clone one `<a>` into several and shift every later index.
     *
     * The WebView parses the rewritten markup once more, so it is parsed here once more too, and its
     * anchors (synthetic href and text, in order) must equal the ones just written. If they don't, the
     * anchors the user would see are not the ones [LinkedHtml.links] describes, and nothing is guessed:
     * every `href` is removed, the list is empty, and no link in this mail can be tapped.
     *
     * `.invalid` is IANA/RFC 2606 reserved and never resolves; [MailWebView] answers a tap with the index
     * alone, never a URL, so no WebView/Chromium canonicalization quirk can match a tap to the wrong entry.
     */
    fun rewriteLinks(html: String): LinkedHtml {
        val doc = Jsoup.parseBodyFragment(html)
        doc.outputSettings().prettyPrint(false)
        val anchors = doc.select("a[href]")
        val links = anchors.mapIndexed { index, anchor ->
            MailLink(TextCleaning.clean(anchor.text()), anchor.attr("href")).also {
                anchor.attr("href", "https://link.invalid/$index")
            }
        }
        val rewritten = doc.body().html()
        if (hrefsAndTexts(Jsoup.parseBodyFragment(rewritten).select("a[href]")) == hrefsAndTexts(anchors)) {
            return LinkedHtml(rewritten, links)
        }
        anchors.removeAttr("href")
        return LinkedHtml(doc.body().html(), emptyList())
    }

    private fun hrefsAndTexts(anchors: Elements): List<Pair<String, String>> = anchors.map { it.attr("href") to it.text() }

    /**
     * The page the WebView loads, with the links its anchors stand for. Links are rewritten last, after
     * the cid images are inlined: [inlineCids] is a text substitution, and running it after the lockstep
     * check in [rewriteLinks] would leave the WebView a body that check never saw.
     */
    fun build(
        sanitizedHtml: String,
        images: Map<String, InlineImage>,
        allowRemoteImages: Boolean,
        theme: MailHtmlTheme,
    ): LinkedHtml {
        val body = rewriteLinks(inlineCids(sanitizedHtml, images))
        val scheme = if (theme.isDark) "dark" else "light"
        return body.copy(
            html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp(allowRemoteImages)}\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>:root{color-scheme:$scheme}" +
                "html,body{margin:0;padding:0;background:${theme.background};color:${theme.foreground}}" +
                "body{padding:12px;font-family:sans-serif;overflow-wrap:anywhere}" +
                "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}</style>" +
                "</head><body>${body.html}</body></html>",
        )
    }

    private fun normalize(id: String) = id.trim().removePrefix("<").removeSuffix(">").lowercase()

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}

/**
 * `#rrggbb` for CSS. Alpha is dropped: the WebView is opaque and a mail has nothing behind it.
 *
 * [java.util.Locale.ROOT] because this is machine-readable output, not text for a reader. `%x`
 * happens not to be one of the conversions `Formatter` localises, so the default locale would
 * give the same six characters today -- pinning it means that stays true of the next edit too.
 */
fun Color.toCssHex(): String = String.format(java.util.Locale.ROOT, "#%06x", toArgb() and 0xFFFFFF)
