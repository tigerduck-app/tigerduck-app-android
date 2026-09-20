package org.ntust.app.tigerduck.ui.screen.mail

import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Host and path of the help pages -- the single source of truth [isGuideUrl] checks against. */
internal const val GUIDE_HOST = "tigerduck.app"
internal const val GUIDE_PATH_PREFIX = "/help/receive-mail/"

/**
 * Host of the help pages. Navigation outside this prefix leaves the WebView for the browser.
 *
 * Derived from [GUIDE_HOST] / [GUIDE_PATH_PREFIX] rather than spelled out again, so this and
 * [isGuideUrl] can never drift apart.
 */
internal const val GUIDE_URL_PREFIX = "https://" + GUIDE_HOST + GUIDE_PATH_PREFIX

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * True when [url] is this app's own help page: `https`, host exactly [GUIDE_HOST], path starting
 * with [GUIDE_PATH_PREFIX].
 *
 * Not a [String.startsWith] against [GUIDE_URL_PREFIX]: that happens to be safe today because the
 * literal hardcodes the scheme, the whole host and the `/` immediately after it -- exactly where a
 * URL's authority ends -- but that safety is a property of the constant's exact spelling, and a
 * future edit to it could silently reopen this to a lookalike host
 * (`https://tigerduck.app.evil.com/...`), a `userinfo` trick
 * (`https://tigerduck.app@evil.com/...`), or similar. Parsing with [java.net.URI] and comparing
 * `scheme`/`host`/`path` as separate fields removes that whole class of bug, and -- deliberately
 * not [android.net.Uri], which returns defaults rather than parsing anything under this module's
 * `isReturnDefaultValues` JVM unit tests -- stays unit-testable without Robolectric. A URL
 * [java.net.URI] cannot parse (or a scheme-relative one, which parses with a `null` scheme) is
 * rejected rather than throwing: fail closed.
 */
internal fun isGuideUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull()?.normalize() ?: return false
    return uri.scheme == "https" &&
        uri.host == GUIDE_HOST &&
        (uri.rawPath ?: "").startsWith(GUIDE_PATH_PREFIX)
}

/**
 * Percent-encodes [value] the way a URL query value needs: everything except the RFC 3986
 * "unreserved" set (letters, digits, `-`, `.`, `_`, `~`) is escaped as `%XX`.
 *
 * Deliberately not [android.net.Uri.encode]: `app/build.gradle.kts` sets
 * `testOptions.unitTests.isReturnDefaultValues = true`, so under the plain JVM unit tests this
 * module runs in, `Uri.encode` returns `null` rather than throwing or doing real work --
 * [guideUrl] would silently build a URL containing the literal text `null`, and a test asserting
 * on the URL's shape would pass for the wrong reason instead of failing loudly. This covers
 * exactly what callers here pass it -- hex colours (`#RRGGBB`) and BCP-47 language tags -- and is
 * written against UTF-8 bytes so a stray non-ASCII input would not get mangled either.
 */
internal fun percentEncode(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            append(c)
        } else {
            append('%').append(HEX_DIGITS[(b shr 4) and 0xF]).append(HEX_DIGITS[b and 0xF])
        }
    }
}

/**
 * The help page, told everything it needs to stop looking like a web page.
 *
 * `bg`/`fg` are the app's own colours rather than anything the site picks: the two palettes are
 * maintained in different repositories and would drift, and a WebView whose background is a shade
 * off the screen behind it shows a seam at its edges.
 *
 * [SchoolMailGuideScreen] passes `background`/`onBackground` -- what its Scaffold fills with, and
 * what every sub-settings screen renders its body on. That is *not* the same pair
 * [SchoolMailMessageScreen] passes into [MailHtmlTheme]: a mail document sits inside a
 * `Surface(color = surface)` card, so there the card's colour is the one that has to match.
 */
internal fun guideUrl(
    platform: String,
    isDark: Boolean,
    languageTag: String,
    background: String,
    foreground: String,
): String = GUIDE_URL_PREFIX + platform +
    "?embed=1&theme=${if (isDark) "dark" else "light"}" +
    "&lang=" + percentEncode(languageTag) +
    "&bg=" + percentEncode(background) +
    "&fg=" + percentEncode(foreground)

/**
 * A WebView onto the `tigerduck-web` help page -- separate from, and deliberately less locked
 * down than, [MailWebView]. That component turns JavaScript, storage and file/content access off
 * because it renders untrusted mail HTML; this one renders a page this team builds and ships as a
 * React SPA, so JavaScript and DOM storage stay on. File and content access stay off, multiple
 * windows stay unsupported and no JavaScript bridge is installed: none of that is needed to show
 * a help page, and each is one more thing to have to reason about if it were added.
 *
 * [WebViewClient.shouldOverrideUrlLoading] keeps navigation inside the help path (see
 * [isGuideUrl]); anything else -- an external link on the page -- is handed to [onExternalLink]
 * instead of being loaded in this WebView.
 *
 * [onError] is reached from two places, both gated on [WebResourceRequest.isForMainFrame] -- a
 * failed sub-resource (an image, a font) is not reason to swap the whole screen for a retry card:
 * [WebViewClient.onReceivedError] for a transport-level failure (no network, DNS, TLS), and
 * [WebViewClient.onReceivedHttpError] for a page the server answered with 4xx or 5xx. The second
 * never fires for the first: a status code is not an error to the loader, so without that
 * override a 404 or a 502 would paint the server's own error body inside the guide screen.
 *
 * Neither can catch a help page that has not been deployed yet. The site serves an SPA fallback,
 * so an unknown path comes back 200 with `index.html`, which is a perfectly successful load as
 * far as the WebView is concerned.
 */
@Composable
fun GuideWebView(
    url: String,
    backgroundColor: Int,
    onExternalLink: (String) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnExternalLink by rememberUpdatedState(onExternalLink)
    val latestOnError by rememberUpdatedState(onError)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(backgroundColor)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportMultipleWindows(false)
                    safeBrowsingEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        if (isGuideUrl(target)) return false
                        latestOnExternalLink(target)
                        return true
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) latestOnError()
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) latestOnError()
                    }
                }
            }
        },
        update = { view ->
            view.setBackgroundColor(backgroundColor)
            if (view.tag != url) {
                view.tag = url
                view.loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}
