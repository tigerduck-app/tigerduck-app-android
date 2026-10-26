package org.ntust.app.tigerduck.ui.screen.files

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.ntust.app.tigerduck.files.FileViewing
import org.ntust.app.tigerduck.files.OfficeRequests
import org.ntust.app.tigerduck.files.ViewerKind
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A Word or PowerPoint document, rendered by the JavaScript libraries the app ships in
 * `assets/viewer/` inside a WebView that reaches nothing else: no network, no files, no window of
 * its own, no bridge to the app. It loads the viewer's page and fetches the one document, both
 * through [OfficeRequests]; the page's title says whether it rendered. Pinch to zoom.
 */
@Composable
internal fun OfficeViewer(uri: Uri, kind: ViewerKind, onFailed: (ViewerFailure) -> Unit) {
    val context = LocalContext.current
    var shown by remember(uri) { mutableStateOf(false) }
    val web = remember(uri) {
        officeWebView(context, uri, kind, onShown = { shown = true }, onFailed = onFailed)
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    // A page that never says how it went (a script that never loaded, say) has failed too.
    LaunchedEffect(web) {
        delay(OFFICE_TIMEOUT_MILLIS)
        if (!shown) onFailed(ViewerFailure(note = "not rendered within ${OFFICE_TIMEOUT_MILLIS / 1000} s"))
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        if (!shown) Reading()
    }
}

/** How long a document may take to render before the viewer gives up on it. */
private const val OFFICE_TIMEOUT_MILLIS = 60_000L

@SuppressLint("SetJavaScriptEnabled")
internal fun officeWebView(
    context: Context,
    uri: Uri,
    kind: ViewerKind,
    onShown: () -> Unit,
    onFailed: (ViewerFailure) -> Unit,
): WebView = WebView(context).apply {
    lockDown(settings)
    val client = OfficeClient(context, uri, kind, onFailed)
    webViewClient = client
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title == "shown") {
                onShown()
            } else {
                // With the document's own read failing, when that's what the page tripped on.
                officeFailure(title)?.let { onFailed(ViewerFailure(client.documentError, note = it)) }
            }
        }
    }
    val name = if (kind == ViewerKind.PPTX) "pptx" else "docx"
    loadUrl("${OfficeRequests.PAGE}?kind=$name")
}

/**
 * What the page's [title] says went wrong, when it says it failed ("failed", or "failed: " and
 * the script's error), or null.
 */
internal fun officeFailure(title: String?): String? {
    if (title == null || !title.startsWith("failed")) return null
    val why = title.removePrefix("failed").removePrefix(":").trim()
    return if (why.isEmpty()) "the document's renderer failed" else "renderer: $why"
}

/** The viewer's WebView: scripts for its renderers, and nothing it could reach out with. */
@SuppressLint("SetJavaScriptEnabled")
internal fun lockDown(settings: WebSettings) = settings.run {
    javaScriptEnabled = true
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    allowFileAccess = false
    allowContentAccess = false
    blockNetworkLoads = true
    domStorageEnabled = false
    setGeolocationEnabled(false)
    mediaPlaybackRequiresUserGesture = true
    cacheMode = WebSettings.LOAD_NO_CACHE
    // A page as wide as a document, fitted to the screen and pinched to zoom.
    useWideViewPort = true
    loadWithOverviewMode = true
    builtInZoomControls = true
    displayZoomControls = false
}

/** Serves the viewer's files and the document, refuses every other request, and follows no link. */
private class OfficeClient(
    private val context: Context,
    private val uri: Uri,
    private val kind: ViewerKind,
    private val onFailed: (ViewerFailure) -> Unit,
) : WebViewClient() {

    /** Why the document itself couldn't be read, when it couldn't. */
    @Volatile
    var documentError: Throwable? = null
        private set

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        when (val route = OfficeRequests.route(request.url.toString())) {
            is OfficeRequests.Route.Asset -> runCatching {
                WebResourceResponse(assetType(route.path), "utf-8", context.assets.open(route.path))
            }.getOrElse { blocked() }
            OfficeRequests.Route.Document -> runCatching {
                val type = if (kind == ViewerKind.PPTX) FileViewing.PPTX_TYPE else FileViewing.DOCX_TYPE
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no stream")
                WebResourceResponse(type, null, ErrorRecordingInputStream(input) { documentError = it })
            }.getOrElse { documentError = it; blocked() }
            OfficeRequests.Route.Blocked -> blocked()
        }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onFailed(ViewerFailure(note = "WebView renderer gone (crashed: ${detail.didCrash()})"))
        return true
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun assetType(path: String) = when (path.substringAfterLast('.')) {
        "html" -> "text/html"
        "css" -> "text/css"
        else -> "text/javascript"
    }
}

/**
 * [input], keeping the error a read fails with ([onError]) before passing it on: WebView reads the
 * document's stream after it's handed over, so a provider that opens it and then fails part way
 * would otherwise leave only the page's own error to show.
 */
internal class ErrorRecordingInputStream(input: InputStream, private val onError: (Throwable) -> Unit) : FilterInputStream(input) {
    override fun read(): Int = recording { super.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = recording { super.read(b, off, len) }

    override fun skip(n: Long): Long = recording { super.skip(n) }

    private inline fun <T> recording(read: () -> T): T = try {
        read()
    } catch (e: IOException) {
        onError(e)
        throw e
    }
}
