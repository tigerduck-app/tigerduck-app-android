package org.ntust.app.tigerduck.ui.screen.mail

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Spec §9.3. JavaScript, storage, file and content access are off; the network is blocked
 * until the user loads images, and even then only the mail's own `<img>` URLs pass
 * [WebViewClient.shouldInterceptRequest].
 *
 * Every `<a href>` in [document] was already rewritten by [MailHtmlDocument.rewriteLinks] to the
 * synthetic form `https://link.invalid/<n>`, `n` being that link's index into the `LinkedHtml.links`
 * returned with the document ([linkCount] is its size). [onLink] is called with `n` -- never a URL -- once
 * [WebViewClient.shouldOverrideUrlLoading] has confirmed the tapped URL is exactly that form
 * (via [parseLinkIndex]) and `n` is within [linkCount]; matching an index this way, rather than
 * a URL by any normalized comparison, means no WebView/Chromium canonicalization quirk can ever
 * cause a tap to be matched to the wrong link or no link at all. Anything else -- a URL that
 * isn't the synthetic form, an index outside `[0, linkCount)`, any other navigation attempt --
 * is ignored outright: no dialog, nothing opens, fail closed.
 *
 * [allowedRemoteUrls] must already be normalized with
 * [SchoolMailMessageViewModel.normalizedHref] -- Chromium hands [shouldInterceptRequest] its own
 * normalized [WebResourceRequest.getUrl] (lowercase host, "/" for an empty path, its own
 * percent-encoding), which a raw `<img src>` string from the sanitized HTML won't match
 * byte-for-byte even when it is the exact same URL, so the request URL is normalized the same
 * way before the membership check.
 */
@Composable
fun MailWebView(
    document: String,
    allowedRemoteUrls: Set<String>,
    linkCount: Int,
    onLink: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnLink by rememberUpdatedState(onLink)
    val allowed by rememberUpdatedState(allowedRemoteUrls)
    val currentLinkCount by rememberUpdatedState(linkCount)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(android.graphics.Color.WHITE)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    blockNetworkLoads = true
                    safeBrowsingEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        parseLinkIndex(request.url.toString(), currentLinkCount)?.let(latestOnLink)
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // loadDataWithBaseURL(null, document, ...) below DOES reach here as a
                        // main-frame `data:` request (Chromium's own
                        // testLoadDataWithBaseUrlTriggersShouldInterceptRequest confirms this) --
                        // the mail renders only because of the `data:` exemption right below, not
                        // because the main frame is skipped. Removing that exemption would blank
                        // every mail. A later main-frame navigation away from the loaded document
                        // is stopped one layer up, by shouldOverrideUrlLoading, before it would
                        // ever reach here as a followable request.
                        if (request.url.scheme == "data") return null
                        if (SchoolMailMessageViewModel.normalizedHref(request.url.toString()) in allowed) return null
                        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
        },
        update = { view ->
            view.settings.blockNetworkLoads = allowed.isEmpty()
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

// Exactly what MailHtmlDocument.rewriteLinks emits: https://link.invalid/<decimal index>, no
// leading zeros, no userinfo/port/query/fragment, nothing else. Anything that doesn't match this
// precisely -- a real href that somehow wasn't rewritten, a redirect, a typo'd scheme -- fails
// closed rather than being treated as some link.
private val LINK_URL = Regex("""^https://link\.invalid/(0|[1-9][0-9]*)$""")

/** See [MailWebView]'s doc. Returns the link index only for the exact synthetic form, in range. */
internal fun parseLinkIndex(url: String, linkCount: Int): Int? {
    val index = LINK_URL.matchEntire(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return index.takeIf { it in 0 until linkCount }
}
