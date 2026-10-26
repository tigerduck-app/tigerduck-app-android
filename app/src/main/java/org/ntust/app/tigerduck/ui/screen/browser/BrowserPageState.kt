package org.ntust.app.tigerduck.ui.screen.browser

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.browser.BrowserZoom
import org.ntust.app.tigerduck.browser.CertificateInfo
import org.ntust.app.tigerduck.browser.UploadSource

/** What the TigerDuck browser shows over or in place of the page. */
@Stable
class BrowserPageState {
    /** The page on screen now, not the link opened, which a redirect leaves behind. */
    var url by mutableStateOf<String?>(null)
    var title by mutableStateOf<String?>(null)

    /** 0 to 100 while loading. */
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** The main frame's certificate, once a page has loaded over https. */
    var certificate by mutableStateOf<CertificateInfo?>(null)

    /** Whether the page is dark, as last read from it; null until known. */
    var pageDark by mutableStateOf<Boolean?>(null)

    var error by mutableStateOf<BrowserError?>(null)
    var find by mutableStateOf<FindState?>(null)

    /** A link off the allowlist the page asked to open, waiting on the student's answer. */
    var outsideUrl by mutableStateOf<String?>(null)

    /** A link long-pressed, for its menu. */
    var linkMenuUrl by mutableStateOf<String?>(null)

    var dialog by mutableStateOf<PageDialog?>(null)
    var upload by mutableStateOf<UploadRequest?>(null)
    var signIn by mutableStateOf<SignInState?>(null)

    /** Whether the page's site has signed the browser in, for the address sheet. */
    var signedIn by mutableStateOf(false)

    /**
     * Whether the page scrolled the bars away ([BrowserChromeCollapse]); the screen still shows them
     * while something over the page needs them, and for TalkBack.
     */
    var barsCollapsed by mutableStateOf(false)

    var zoom by mutableIntStateOf(BrowserZoom.DEFAULT)
    var desktopSite by mutableStateOf(false)

    val host: String? get() = url?.toHttpUrlOrNull()?.host
    val isHttps: Boolean get() = url?.toHttpUrlOrNull()?.isHttps == true

    /** Whether the page's certificate was refused: then nothing calls its connection secure. */
    val certificateRefused: Boolean get() = error is BrowserError.BadCertificate

    /** The certificate View certificate shows: the refused one while it is refused, never the page's before. */
    val shownCertificate: CertificateInfo?
        get() = when (val refused = error) {
            is BrowserError.BadCertificate -> refused.certificate
            else -> certificate
        }
}

sealed interface BrowserError {
    val host: String?

    /** The page did not load: no network, a server not answering. */
    data class Unreachable(override val host: String?) : BrowserError

    /** The page's [certificate] failed, when the WebView gave it; the page is never shown. */
    data class BadCertificate(override val host: String?, val certificate: CertificateInfo? = null) : BrowserError
}

/** Find in page: what is looked for, which match is on screen (from 1) and how many there are. */
data class FindState(val query: String = "", val ordinal: Int = 0, val count: Int = 0, val done: Boolean = true)

/** A page's `alert`, `confirm` or `prompt`, answered once through [answer]. */
data class PageDialog(
    val kind: Kind,
    val host: String?,
    val message: String,
    val defaultValue: String? = null,
    val answer: (confirmed: Boolean, value: String?) -> Unit,
) {
    enum class Kind { ALERT, CONFIRM, PROMPT }
}

/** A page's file field asking for a file, answered once through [deliver]. */
data class UploadRequest(
    val host: String?,
    val sources: List<UploadSource>,
    val imagesOnly: Boolean,
    val acceptTypes: List<String>,
    val multiple: Boolean,
    val deliver: (Array<Uri>?) -> Unit,
)

/**
 * The browser's own sign-in screen, in place of a site's sign-in page: [working] while a try is
 * on its way, and otherwise the automatic sign-in failed, for Retry.
 */
data class SignInState(
    val siteName: String,
    val target: String,
    val working: Boolean = false,
)
