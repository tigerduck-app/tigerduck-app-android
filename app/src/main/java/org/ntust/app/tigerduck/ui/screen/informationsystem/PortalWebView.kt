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

    /** The page actually on screen right now — not the tapped link's URL, which an SSO
     *  redirect can leave stale for as long as the login bounce is in progress. */
    var currentUrl by mutableStateOf<String?>(null)
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
    /**
     * Credentials for [buildSsoAutoFillScript]'s one-shot fill-and-submit, or null to disable
     * auto-fill entirely (e.g. no signed-in session to auto-fill with). Read fresh out of
     * [rememberUpdatedState] on every recomposition rather than captured once, same as
     * [onError] below — a stale closure would auto-fill with whatever credentials were live
     * when the WebView was first created.
     */
    studentId: String? = null,
    password: String? = null,
) {
    val latestOnError by rememberUpdatedState(onError)
    val latestStudentId by rememberUpdatedState(studentId)
    val latestPassword by rememberUpdatedState(password)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Per-WebView, not per-composition: this must survive exactly as long as the
            // WebViewClient does, to guard the one thing that matters — never auto-submitting
            // the same login page twice in a row (a wrong stored password would otherwise
            // retry forever against NTUST's own login endpoint).
            var lastAutoFillUrl: String? = null
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // The HTTP cache is a disk-backed store shared across every WebView in the
                // process — a freshly constructed instance does NOT start empty, the same way
                // CookieManager doesn't (see syncCookiesToWebView). A stale cached redirect or
                // error response from a previous failed SSO attempt on this same portal link
                // would otherwise keep being served here instead of a fresh request. This is
                // the only WebView this screen ever creates, so clearing on every open/retry
                // (this factory reruns on the initial open and on every key(reloadKey) reload)
                // never touches Mail's or the library's own WebViews.
                clearCache(true)
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
                        state.currentUrl = url
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        state.isLoading = false
                        state.canGoBack = view.canGoBack()
                        state.pageTitle = view.title
                        state.currentUrl = url

                        val user = latestStudentId
                        val pass = latestPassword
                        val isSso = isSsoLoginUrl(url)
                        android.util.Log.d(
                            "PortalWebView",
                            "onPageFinished url=$url isSso=$isSso hasCreds=${user != null && pass != null} lastAutoFillUrl=$lastAutoFillUrl",
                        )
                        if (isSso && user != null && pass != null && url != lastAutoFillUrl) {
                            // Marked before the async evaluateJavascript call resolves, not
                            // after: a slow page could otherwise fire onPageFinished a second
                            // time (a redirect, a resource still settling) before the first
                            // fill's callback returns, and both would submit the form.
                            lastAutoFillUrl = url
                            view.evaluateJavascript(buildSsoAutoFillScript(user, pass)) { result ->
                                android.util.Log.d("PortalWebView", "autofill result=$result")
                            }
                        }
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
