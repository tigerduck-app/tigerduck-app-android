package org.ntust.app.tigerduck.ui.screen.mail

import org.jsoup.Jsoup
import org.ntust.app.tigerduck.mail.model.InlineImage
import java.net.URLDecoder
import java.util.Base64

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
     * Replaces every `<a href>` with `https://link.invalid/<n>`, `n` being that link's 0-based
     * index in document order. This is the same order `HtmlSanitizer.sanitize` built
     * `SanitizedHtml.links` in (`clean.select("a[href]")`, a document-order traversal): re-running
     * the identical `a[href]` selection on this already-sanitized markup -- itself the serialized
     * output of that same `clean` document -- walks the DOM in that identical order again, so the
     * index written here and the index into `SanitizedHtml.links` the view model reads back always
     * agree, without either side having to trust the other's count.
     *
     * `.invalid` is IANA/RFC 2606 reserved and can never resolve, so a rewritten link that somehow
     * reached the network anyway (it can't: [MailWebView]'s request interception blocks anything
     * not on the image allowlist) would still go nowhere. [MailWebView.shouldOverrideUrlLoading]
     * then answers a tap with the index alone -- never the href -- so no WebView/Chromium URL
     * canonicalization quirk can cause a tapped link to fail to match its own entry.
     */
    fun rewriteLinks(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("a[href]").forEachIndexed { index, element -> element.attr("href", "https://link.invalid/$index") }
        doc.outputSettings().prettyPrint(false)
        return doc.body().html()
    }

    fun build(sanitizedHtml: String, images: Map<String, InlineImage>, allowRemoteImages: Boolean): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp(allowRemoteImages)}\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;background:#fff;color:#000}" +
            "body{padding:12px;font-family:sans-serif;overflow-wrap:anywhere}" +
            "img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}</style>" +
            "</head><body>${inlineCids(rewriteLinks(sanitizedHtml), images)}</body></html>"

    private fun normalize(id: String) = id.trim().removePrefix("<").removeSuffix(">").lowercase()

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
