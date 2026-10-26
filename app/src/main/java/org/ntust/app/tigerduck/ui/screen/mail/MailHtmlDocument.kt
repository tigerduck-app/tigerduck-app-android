package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.InlineImage
import org.ntust.app.tigerduck.mail.sanitize.MailLink
import org.ntust.app.tigerduck.mail.sanitize.MailReadableHtml
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
 * dark room -- or [LIGHT]'s white paper, when the reader asks for it. A sender's colors are kept
 * wherever they read: only a text color under 7:1 against what it is drawn on moves, to the
 * nearest lightness that reads, keeping its hue (see [MailReadableHtml]). Backgrounds, images
 * and every other color are left as sent, so logos, screenshots and branded mail are not
 * distorted.
 * [isDark] drives `color-scheme`, which is what makes mail that opts into `prefers-color-scheme`
 * follow along, and makes UA-default form controls legible.
 */
data class MailHtmlTheme(
    val background: String,
    val foreground: String,
    val isDark: Boolean,
    /**
     * The color the page draws a link in. The page's own CSS sets it, so a link on a sender's
     * background has its contrast judged in the color it is drawn in. By default it is the one
     * Chromium draws a link in under [isDark]'s `color-scheme`, as the page did before setting
     * one, and as the Apple app's WebKit does -- moved, as the readability pass moves text, where
     * that does not read at 7:1 on [background]. The Apple app's dark page is black, where it
     * does; the default dark theme's surface is not, and its links would be 4.75:1.
     */
    val link: String = linkOn(background, isDark),
) {
    companion object {
        /** Chromium's link color under `color-scheme: light`. */
        const val LIGHT_LINK = "#0000ee"

        /** Chromium's link color under `color-scheme: dark`. */
        const val DARK_LINK = "#9e9eff"

        private fun linkOn(background: String, isDark: Boolean): String {
            val chromium = if (isDark) DARK_LINK else LIGHT_LINK
            val page = MailReadableHtml.parse(background, legacy = false) ?: return chromium
            val link = MailReadableHtml.parse(chromium, legacy = false) ?: return chromium
            return MailReadableHtml.readable(link, page)?.css ?: chromium
        }

        /** [LIGHT]'s paper, for the Surface and WebView drawn behind its document. */
        val LIGHT_PAPER = Color.White

        /**
         * White paper: "View in light mode". A mail that sets colors of its own
         * ([MailReadableHtml.setsColors]) opens on it in a dark app, as in the Apple app: it is
         * the page such a mail was written against, where its colors need the fewest fixes.
         */
        val LIGHT = MailHtmlTheme(background = LIGHT_PAPER.toCssHex(), foreground = Color.Black.toCssHex(), isDark = false)
    }

    /**
     * The page a mail is drawn on in this app theme: [LIGHT]'s white paper when the reader views
     * it in light mode while this theme is dark, else this theme's own. A light theme's surface
     * already is a light page, so there the choice waits until the app turns dark again.
     */
    fun page(viewInLight: Boolean): MailHtmlTheme = if (viewInLight && isDark) LIGHT else this
}

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
    fun rewriteLinks(html: String): LinkedHtml = rewritten(html).linked

    /** [rewriteLinks]'s result, and whether it had to strip every href. */
    private class Rewritten(val linked: LinkedHtml, val stripped: Boolean)

    private fun rewritten(html: String): Rewritten {
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
            return Rewritten(LinkedHtml(rewritten, links), stripped = false)
        }
        anchors.removeAttr("href")
        return Rewritten(LinkedHtml(doc.body().html(), emptyList()), stripped = true)
    }

    private fun hrefsAndTexts(anchors: Elements): List<Pair<String, String>> = anchors.map { it.attr("href") to it.text() }

    /**
     * The page the WebView loads, with the links its anchors stand for. Its text is first made readable
     * on [theme] ([MailReadableHtml]). Links are rewritten last, after the cid images are inlined:
     * [inlineCids] is a text substitution, and running it after the lockstep check in [rewriteLinks]
     * would leave the WebView a body that check never saw.
     */
    fun build(
        sanitizedHtml: String,
        images: Map<String, InlineImage>,
        allowRemoteImages: Boolean,
        theme: MailHtmlTheme,
    ): LinkedHtml = build(MailReadableHtml(sanitizedHtml), images, allowRemoteImages, theme)

    /**
     * [build] from a sanitized mail already read for its colors, which the view model reads first
     * to choose the page it opens on.
     */
    fun build(
        readable: MailReadableHtml,
        images: Map<String, InlineImage>,
        allowRemoteImages: Boolean,
        theme: MailHtmlTheme,
    ): LinkedHtml {
        val rewritten = rewritten(inlineCids(readable.on(theme), images))
        // Every href stripped (see rewriteLinks): the anchors were judged as links, drawn in the
        // link color, and are text now, drawn in the color around them -- which may not read on
        // a background of their own. So the page is judged again as it now is.
        val body = if (rewritten.stripped) {
            LinkedHtml(MailReadableHtml(rewritten.linked.html).on(theme), emptyList())
        } else {
            rewritten.linked
        }
        val scheme = if (theme.isDark) "dark" else "light"
        return body.copy(
            html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp(allowRemoteImages)}\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>:root{color-scheme:$scheme}" +
                "html,body{margin:0;padding:0;background:${theme.background};color:${theme.foreground}}" +
                "body{padding:12px;font-family:sans-serif;overflow-wrap:anywhere}a[href]{color:${theme.link}}" +
                "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}</style>" +
                "</head><body>${body.html}</body></html>",
        )
    }

    private fun MailReadableHtml.on(theme: MailHtmlTheme): String =
        adjust(pageBackgroundHex = theme.background, pageForegroundHex = theme.foreground, linkForegroundHex = theme.link)

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
