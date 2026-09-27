package org.ntust.app.tigerduck.ui.screen.informationsystem

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Generic Android/Chrome UA string, matching what
 * [org.ntust.app.tigerduck.network.NtustSessionManager]'s OkHttp interceptor sends for
 * non-Moodle NTUST hosts. The cookies bridged into this WebView (see [syncCookiesToWebView])
 * were minted under that UA — a mismatched WebView UA risks NTUST's edge rejecting or
 * redirecting the session, the same anti-bot behavior the interceptor already works around
 * for Moodle.
 */
internal const val PORTAL_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** Read/drive handle for a [PortalWebView] instance, exposed to the hosting screen's toolbar. */
class PortalWebViewState {
    var canGoBack by mutableStateOf(false)
        internal set
    var isLoading by mutableStateOf(false)
        internal set
    var progress by mutableIntStateOf(0)
        internal set
    var pageTitle by mutableStateOf<String?>(null)
        internal set

    internal var webView: WebView? = null

    /** True if a page-internal back navigation was performed (caller should not pop the screen). */
    fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    fun reload() {
        webView?.reload()
    }
}

@Composable
fun rememberPortalWebViewState(): PortalWebViewState = remember { PortalWebViewState() }

/**
 * The information-system portal's own WebView. Based on
 * [org.ntust.app.tigerduck.ui.screen.mail.GuideWebView]'s "trusted first-party-ish content"
 * profile (JS + DOM storage on, file/content access off, safe browsing on) — this renders
 * NTUST's own portal pages, not untrusted mail HTML, so
 * [org.ntust.app.tigerduck.ui.screen.mail.MailWebView]'s fully locked-down profile isn't the
 * right template here.
 */
@Composable
fun PortalWebView(
    url: String,
    state: PortalWebViewState,
    backgroundColor: Int,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnError by rememberUpdatedState(onError)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(backgroundColor)
                settings.apply {
                    // Suppressed knowingly: this renders NTUST's own portal pages, not
                    // untrusted content — see the class doc.
                    @SuppressLint("SetJavaScriptEnabled")
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportMultipleWindows(false)
                    safeBrowsingEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = PORTAL_WEBVIEW_USER_AGENT
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        state.isLoading = true
                        state.canGoBack = view.canGoBack()
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        state.isLoading = false
                        state.canGoBack = view.canGoBack()
                        state.pageTitle = view.title
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
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
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        state.progress = newProgress
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.pageTitle = title
                    }
                }
                state.webView = this
            }
        },
        update = { view ->
            view.setBackgroundColor(backgroundColor)
            if (view.tag != url) {
                view.tag = url
                view.loadUrl(url)
            }
        },
        onRelease = {
            state.webView = null
            it.destroy()
        },
    )
}
