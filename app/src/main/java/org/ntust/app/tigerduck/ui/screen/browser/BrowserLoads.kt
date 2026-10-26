package org.ntust.app.tigerduck.ui.screen.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.browser.CertificateInfo

/**
 * What the browser keeps about its main-frame loads: the page Retry and "Open in browser" name
 * ([loadingUrl]), the error shown over the page, and the page a sign-in goes on to
 * ([signInTarget]). Apart from the activity so a test can run it.
 *
 * The WebView reports a page's loads whether or not the browser was asked about them: a form's
 * POST and a move through the history only start. A failed load's error comes before the start of
 * its error page on some WebViews and after it on others, so an error stays up through the start
 * of the page it is for, and any other start takes it down.
 */
internal class BrowserLoads(private val state: BrowserPageState, private val signIns: BrowserSignIns) {
    /** The page loaded, or loading, last, by its [address]. */
    val loadingUrl: String? get() = current?.let(::address)

    /** The page loaded, or loading, last, as the WebView names it. */
    private var current: String? = null

    /** The page a sign-in under way goes on to; null once a page of its site shows. */
    var signInTarget: String? = null

    private var startedUrl: String? = null

    /** The main-frame request out last, till a page starts or a navigation supersedes it; written off the main thread. */
    @Volatile
    private var mainFrameRequest: String? = null

    /** A page whose error came before the page started. */
    private var failedBeforeStart: String? = null

    /**
     * The last page asked for that a sign-in could go on to, by a load, a page's navigation or a
     * form's POST: kept through its site's sign-in pages, SSO's authorize on the way to its form
     * among them, which would otherwise stand for it, and dropped for a page no provider signs in.
     * Written off the main thread too.
     */
    @Volatile
    private var intended: String? = null

    private fun asked(url: String) {
        val provider = signIns.forUrl(url)
        when {
            provider == null -> intended = null
            provider.isReturnPage(url) -> intended = url
        }
    }

    /** A load the app chose, or a page's navigation the browser let through. */
    fun requested(url: String) {
        asked(url)
        current = url
        // A navigation of its own, or the redirect a request went to: the request is no longer the page's.
        mainFrameRequest = null
        failedBeforeStart = null
    }

    /**
     * A main-frame request for [url] goes out (shouldInterceptRequest, off the main thread): a
     * form's POST included, which nothing else reports before its page starts.
     */
    fun mainFrameRequested(url: String) {
        asked(url)
        mainFrameRequest = url
    }

    /** [url]'s page started. */
    fun started(url: String) {
        current = url
        // The page it was for has started, or another has instead.
        mainFrameRequest = null
        if (failedBeforeStart != url) state.error = null
        failedBeforeStart = null
        startedUrl = url
        // A page of the site, past its sign-in links: whatever needs a sign-in next asks from the page it is on.
        if (signIns.forUrl(url)?.isReturnPage(url) == true) signInTarget = null
    }

    /** [url]'s page finished, failed or not. */
    fun finished(url: String) {
        if (url == startedUrl) startedUrl = null
    }

    /**
     * The WebView refused [url]'s certificate; true when that was the page's own, which then shows
     * the certificate error. The WebView asks about an image or a script on the page too: refused
     * all the same, it leaves the page as it is.
     */
    fun certificateFailed(url: String?, host: String?, certificate: CertificateInfo? = null): Boolean {
        if (url == null || (url != current && url != state.url && url != mainFrameRequest)) return false
        failed(url, BrowserError.BadCertificate(host, certificate))
        return true
    }

    /**
     * The address [url] goes by in the bars, the address sheet, Retry and "Open in browser": a
     * site's sign-in link stands for the page it signs in to, or the site itself.
     */
    fun address(url: String): String {
        if (signIns.forUrl(url)?.isSignInLink(url) != true) return url
        return signInTarget ?: url.toHttpUrlOrNull()?.resolve("/")?.toString() ?: url
    }

    /** The main-frame load of [url] failed with [error]. */
    fun failed(url: String?, error: BrowserError) {
        state.error = error
        if (url == null) return
        current = url
        failedBeforeStart = if (url == startedUrl) null else url
    }

    /**
     * The page the site's sign-in page at [url] came in the way of, for the sign-in to go on to:
     * the one a sign-in under way is for, or else the last one asked for ([intended]), or the page
     * being loaded or shown, where the site can go back to it. Never one of the site's sign-in
     * links, which work once.
     */
    fun signInReturn(url: String): String {
        val provider = signIns.forUrl(url)
        val returnable = { page: String? -> page?.takeIf { provider?.isReturnPage(it) == true } }
        return signInTarget ?: returnable(intended) ?: returnable(current) ?: returnable(state.url) ?: url
    }
}
